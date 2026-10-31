import { readFileSync, statSync, writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

// Offline evaluator only. It never runs ASR/LLMs, repairs transcripts, or emits source text/facts.
const KINDS = ['DATE','TIME','END_TIME','PERSON_NAME','COUNTERPARTY','RECIPIENT','DEADLINE','NEGATION','ACTION','AMOUNT','PHONE_NUMBER','EMAIL_ADDRESS','LOCATION'];
const STATES = new Set(['AFFIRMED','NEGATED','CONDITIONAL','SUPERSEDED','UNKNOWN']);
function check(ok, code) { if (!ok) throw new Error(code); }
function text(value) { check(typeof value === 'string' && value.length <= 24000 && !value.includes('\0'), 'invalid_or_oversized_text'); return value.normalize('NFC'); }
function rate(errors, denominator) { return denominator ? errors / denominator : null; }

/** Deterministic Levenshtein S/D/I; ties prefer diagonal, deletion, then insertion. */
export function editCounts(reference, hypothesis) {
  check(Array.isArray(reference) && Array.isArray(hypothesis), 'tokens_required');
  const n=reference.length, m=hypothesis.length;
  check((n+1)*(m+1) <= 4_000_000, 'segment_evaluation_into_short_aligned_utterances');
  let prev=Array.from({length:4},()=>new Int32Array(m+1));
  let next=Array.from({length:4},()=>new Int32Array(m+1));
  for(let j=0;j<=m;j++){prev[0][j]=j;prev[3][j]=j;}
  for(let i=1;i<=n;i++){
    next.forEach(row=>row.fill(0));next[0][0]=i;next[2][0]=i;
    for(let j=1;j<=m;j++){
      const sub=reference[i-1]===hypothesis[j-1]?0:1;
      const diagonal=prev[0][j-1]+sub, deletion=prev[0][j]+1, insertion=next[0][j-1]+1;
      if(diagonal<=deletion&&diagonal<=insertion){
        next[0][j]=diagonal;next[1][j]=prev[1][j-1]+sub;next[2][j]=prev[2][j-1];next[3][j]=prev[3][j-1];
      }else if(deletion<=insertion){
        next[0][j]=deletion;next[1][j]=prev[1][j];next[2][j]=prev[2][j]+1;next[3][j]=prev[3][j];
      }else{
        next[0][j]=insertion;next[1][j]=next[1][j-1];next[2][j]=next[2][j-1];next[3][j]=next[3][j-1]+1;
      }
    }
    [prev,next]=[next,prev];
  }
  return {substitutions:prev[1][m],deletions:prev[2][m],insertions:prev[3][m],errors:prev[0][m],referenceUnits:n,rate:rate(prev[0][m],n)};
}
export function transcriptMetrics(referenceText,hypothesisText){
  const reference=text(referenceText), hypothesis=text(hypothesisText);
  const words=value=>value.trim()?value.trim().split(/\s+/u):[];
  return {
    strictCer:editCounts(Array.from(reference),Array.from(hypothesis)),
    koreanCer:editCounts(Array.from(reference.replace(/\s/gu,'')),Array.from(hypothesis.replace(/\s/gu,''))),
    wer:editCounts(words(reference),words(hypothesis)),
    silenceFalseSpeech:reference.trim()===''&&hypothesis.trim()!=='',
  };
}
function factKey(fact,includeState=true){
  check(fact&&KINDS.includes(fact.kind),'invalid_fact_kind');
  check(typeof fact.role==='string'&&/^[a-z][a-z0-9_.-]{0,63}$/u.test(fact.role),'invalid_fact_role');
  check(STATES.has(fact.state),'invalid_fact_state');
  const value=text(fact.value).trim();check(value.length>0&&value.length<=512,'invalid_fact_value');
  return JSON.stringify([fact.kind,fact.role,value,...(includeState?[fact.state]:[])]);
}
function prf(tp,fp,fn){const precision=tp+fp?tp/(tp+fp):null,recall=tp+fn?tp/(tp+fn):null;
  return {tp,fp,fn,precision,recall,f1:precision!==null&&recall!==null&&precision+recall>0?2*precision*recall/(precision+recall):tp+fp+fn?0:null};}
export function criticalMetrics(referenceFacts,predictedFacts){
  check(Array.isArray(referenceFacts)&&Array.isArray(predictedFacts)&&referenceFacts.length<=200&&predictedFacts.length<=200,'bounded_fact_arrays_required');
  const reference=new Map(referenceFacts.map(f=>[factKey(f),f]));
  const predicted=new Map(predictedFacts.map(f=>[factKey(f),f]));
  const byKind={};
  for(const kind of KINDS){
    const r=new Set([...reference].filter(([,f])=>f.kind===kind).map(([k])=>k));
    const p=new Set([...predicted].filter(([,f])=>f.kind===kind).map(([k])=>k));
    const tp=[...p].filter(k=>r.has(k)).length;
    byKind[kind]=prf(tp,p.size-tp,r.size-tp);
  }
  let polarityFlips=0,unsupportedAffirmativeActions=0,evidenceMissing=0;
  for(const [key,p] of predicted){
    const related=[...reference.values()].filter(r=>factKey(r,false)===factKey(p,false));
    if(related.some(r=>r.state!==p.state&&new Set([r.state,p.state]).has('NEGATED')&&new Set([r.state,p.state]).has('AFFIRMED')))polarityFlips++;
    if(p.kind==='ACTION'&&p.state==='AFFIRMED'&&!reference.has(key))unsupportedAffirmativeActions++;
    // evidenceValid must be set by the host validator, never a model self-score.
    if(p.evidenceValid!==true)evidenceMissing++;
  }
  return {byKind,polarityFlips,unsupportedAffirmativeActions,evidenceMissing};
}
function intervals(items,durationMs){
  check(Array.isArray(items)&&items.length<=2000,'bounded_intervals_required');
  const sorted=items.map(pair=>{check(Array.isArray(pair)&&pair.length===2&&pair.every(Number.isFinite)&&pair[0]>=0&&pair[1]>pair[0]&&pair[1]<=durationMs,'invalid_interval');return [...pair];}).sort((a,b)=>a[0]-b[0]);
  const merged=[];for(const item of sorted){const tail=merged.at(-1);if(tail&&item[0]<=tail[1])tail[1]=Math.max(tail[1],item[1]);else merged.push(item);}return merged;
}
function length(items){return items.reduce((sum,[a,b])=>sum+b-a,0);}
export function speechIntervalMetrics(reference,predicted,durationMs){
  check(Number.isFinite(durationMs)&&durationMs>0&&durationMs<=7_200_000,'invalid_duration');
  const r=intervals(reference,durationMs),p=intervals(predicted,durationMs);let overlap=0,i=0,j=0;
  while(i<r.length&&j<p.length){overlap+=Math.max(0,Math.min(r[i][1],p[j][1])-Math.max(r[i][0],p[j][0]));if(r[i][1]<p[j][1])i++;else j++;}
  const refMs=length(r),predMs=length(p);
  return {referenceSpeechMs:refMs,predictedSpeechMs:predMs,matchedSpeechMs:overlap,missedSpeechMs:refMs-overlap,falseSpeechMs:predMs-overlap,
    speechRecall:rate(overlap,refMs),speechPrecision:rate(overlap,predMs)};
}
/** A diagnostic candidate, NEVER an instruction to delete overlapping recognized text. */
export function boundaryOverlapCandidate(left,right){
  const a=Array.from(text(left).trim()),b=Array.from(text(right).trim());
  for(let size=Math.min(a.length,b.length,120);size>=4;size--){if(a.slice(-size).join('')===b.slice(0,size).join(''))return size;}return 0;
}
function addMetric(total,part){for(const field of ['substitutions','deletions','insertions','errors','referenceUnits'])total[field]+=part[field];total.rate=rate(total.errors,total.referenceUnits);}
function emptyMetric(){return {substitutions:0,deletions:0,insertions:0,errors:0,referenceUnits:0,rate:null};}
export function scoreDataset(input){
  check(input?.schemaVersion===1&&Array.isArray(input.cases)&&input.cases.length>0&&input.cases.length<=1000,'invalid_dataset');
  const seen=new Set(),cer=emptyMetric(),koCer=emptyMetric(),wer=emptyMetric(),totals=Object.fromEntries(KINDS.map(k=>[k,{tp:0,fp:0,fn:0}]));
  let silenceFalseSpeechCases=0,annotatedCases=0,extractionNotRun=0,polarityFlips=0,unsupportedAffirmativeActions=0,evidenceMissing=0;
  let wallMs=0,audioMs=0,measuredCases=0;
  const cases=input.cases.map((record,index)=>{
    check(typeof record.id==='string'&&/^[A-Za-z0-9_.-]{1,80}$/u.test(record.id)&&!seen.has(record.id),'invalid_or_duplicate_case_id');seen.add(record.id);
    const transcript=transcriptMetrics(record.referenceText,record.hypothesisText);
    addMetric(cer,transcript.strictCer);addMetric(koCer,transcript.koreanCer);addMetric(wer,transcript.wer);if(transcript.silenceFalseSpeech)silenceFalseSpeechCases++;
    let critical=null;
    if(record.annotationComplete===true&&record.extractionStatus==='completed'){
      critical=criticalMetrics(record.referenceFacts,record.predictedFacts);annotatedCases++;
      for(const k of KINDS)for(const field of ['tp','fp','fn'])totals[k][field]+=critical.byKind[k][field];
      polarityFlips+=critical.polarityFlips;unsupportedAffirmativeActions+=critical.unsupportedAffirmativeActions;evidenceMissing+=critical.evidenceMissing;
    }else extractionNotRun++;
    let rtf=null;
    if(record.measurements){const m=record.measurements;check(Number.isFinite(m.wallMs)&&m.wallMs>=0&&Number.isFinite(m.audioMs)&&m.audioMs>0,'invalid_real_measurements');wallMs+=m.wallMs;audioMs+=m.audioMs;measuredCases++;rtf=m.wallMs/m.audioMs;}
    const vad=record.vad? speechIntervalMetrics(record.vad.reference,record.vad.predicted,record.vad.durationMs):null;
    return {caseIndex:index+1,transcript,critical,rtf,vad};
  });
  return {schemaVersion:1,scope:'offline_scoring_only_no_asr_executed',normalization:'NFC; Korean CER removes whitespace only; WER whitespace-split; punctuation/digits/negation unchanged',
    caseCount:cases.length,transcript:{strictCer:cer,koreanCer:koCer,wer,silenceFalseSpeechCases},
    extraction:{annotatedCases,notScoredCases:extractionNotRun,byKind:Object.fromEntries(KINDS.map(k=>[k,prf(totals[k].tp,totals[k].fp,totals[k].fn)])),polarityFlips,unsupportedAffirmativeActions,evidenceMissing},
    performance:{measuredCases,unmeasuredCases:cases.length-measuredCases,audioMs:audioMs||null,wallMs:measuredCases?wallMs:null,rtf:measuredCases?wallMs/audioMs:null},
    semanticAccuracyGuaranteed:false,automaticActionApproval:false,privateTextOmitted:true,cases};
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
  try{
    const args=process.argv.slice(2);check(args.length===1||args.length===3&&args[1]==='--output','usage_score_input_json_optional_output');
    check(statSync(args[0]).size<=16*1024*1024,'dataset_exceeds_16MiB');
    const input=JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(readFileSync(args[0])));
    const result=JSON.stringify(scoreDataset(input),null,2)+'\n';
    if(args.length===3)writeFileSync(args[2],result,{flag:'wx'});else process.stdout.write(result);
  }catch(error){
    const code=error instanceof SyntaxError?'invalid_json':typeof error.code==='string'&&/^[A-Z0-9_]+$/u.test(error.code)?`io_${error.code}`:
      typeof error.message==='string'&&/^[a-z0-9_]+$/u.test(error.message)?error.message:'evaluation_failed';
    process.stderr.write(JSON.stringify({failed:true,code})+'\n');process.exitCode=1;
  }
}
