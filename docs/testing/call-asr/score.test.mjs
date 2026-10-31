import test from 'node:test';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {editCounts,transcriptMetrics,criticalMetrics,speechIntervalMetrics,boundaryOverlapCandidate,scoreDataset} from './score.mjs';

test('edit counts preserve substitutions, deletions and insertions',()=>{
  assert.equal(editCounts(['a','b'],['a','c']).substitutions,1);
  assert.equal(editCounts(['a','b'],['a']).deletions,1);
  assert.equal(editCounts(['a'],['a','b']).insertions,1);
});
test('Korean spacing tolerance does not erase missing negation or changed time',()=>{
  assert.equal(transcriptMetrics('인수 인계','인수인계').koreanCer.errors,0);
  assert.notEqual(transcriptMetrics('보내지 마세요','보내세요').koreanCer.errors,0);
  assert.notEqual(transcriptMetrics('오후 3시','오후 4시').koreanCer.errors,0);
});
test('Unicode NFC equivalence and emoji code points are handled consistently',()=>{
  assert.equal(transcriptMetrics('가','가').strictCer.errors,0);
  assert.equal(transcriptMetrics('📋','📌').strictCer.substitutions,1);
});
test('silence hallucinations have insertion counts and an undefined empty-reference rate',()=>{
  const score=transcriptMetrics('','안녕하세요');assert.equal(score.silenceFalseSpeech,true);assert.equal(score.koreanCer.insertions,5);assert.equal(score.koreanCer.rate,null);
});
const fact=(kind,value,state='AFFIRMED')=>({kind,role:'appointment',value,state,evidenceValid:true});
test('negative-to-affirmative action flip cannot become a true positive',()=>{
  const score=criticalMetrics([fact('ACTION','send-report','NEGATED')],[fact('ACTION','send-report')]);
  assert.equal(score.byKind.ACTION.tp,0);assert.equal(score.byKind.ACTION.fp,1);assert.equal(score.byKind.ACTION.fn,1);assert.equal(score.polarityFlips,1);assert.equal(score.unsupportedAffirmativeActions,1);
});
test('corrected time with the old value is a critical false positive and omission',()=>{
  const score=criticalMetrics([fact('TIME','15:30')],[fact('TIME','15:00')]);assert.equal(score.byKind.TIME.fp,1);assert.equal(score.byKind.TIME.fn,1);
});
test('start and end times remain separate critical slots',()=>{
  const score=criticalMetrics([fact('TIME','15:30'),fact('END_TIME','16:30')],[fact('TIME','16:30'),fact('END_TIME','15:30')]);
  assert.equal(score.byKind.TIME.fp,1);assert.equal(score.byKind.END_TIME.fp,1);assert.equal(score.byKind.END_TIME.fn,1);
});
test('identical duplicate facts do not inflate precision or recall',()=>{
  const f=fact('DATE','2030-01-02');const score=criticalMetrics([f],[f,f]);assert.equal(score.byKind.DATE.tp,1);
});
test('source support is scored independently from a semantically matching value',()=>{
  const f=fact('PERSON_NAME','가상담당자');const score=criticalMetrics([f],[{...f,evidenceValid:false}]);assert.equal(score.byKind.PERSON_NAME.tp,1);assert.equal(score.evidenceMissing,1);
});
test('VAD scoring merges intersections without double counting and detects lost speech',()=>{
  const score=speechIntervalMetrics([[100,400],[300,600]],[[200,500]],1000);assert.equal(score.referenceSpeechMs,500);assert.equal(score.missedSpeechMs,200);assert.equal(score.speechRecall,0.6);
});
test('overlap candidates preserve original repetitions and never rewrite negation',()=>{
  const left='자료는 보내지 마세요',right='보내지 마세요. 내일 확인합니다';const before=[left,right];
  assert.ok(boundaryOverlapCandidate(left,right)>=4);assert.deepEqual([left,right],before);assert.equal(boundaryOverlapCandidate('네','네'),0);
});
test('missing extraction and unmeasured audio never appear as perfect accuracy or real-time speed',()=>{
  const score=scoreDataset({schemaVersion:1,cases:[{id:'synthetic-1',referenceText:'회의',hypothesisText:'회의',annotationComplete:true,extractionStatus:'not_run'}]});
  assert.equal(score.extraction.annotatedCases,0);assert.equal(score.extraction.byKind.ACTION.f1,null);assert.equal(score.performance.rtf,null);assert.equal(score.automaticActionApproval,false);
});
test('metrics omit private references, values and hypotheses',()=>{
  const secret='SYNTHETIC_PRIVATE_MARKER_123';const score=scoreDataset({schemaVersion:1,cases:[{id:secret,referenceText:secret,hypothesisText:secret,annotationComplete:true,extractionStatus:'completed',referenceFacts:[fact('PERSON_NAME',secret)],predictedFacts:[fact('PERSON_NAME',secret)]}]});
  assert.equal(JSON.stringify(score).includes(secret),false);
});
test('CLI file errors omit potentially private paths',()=>{
  const result=spawnSync(process.execPath,[fileURLToPath(new URL('./score.mjs',import.meta.url)),'MISSING_PRIVATE_PATH_987/corpus.json'],{encoding:'utf8'});
  assert.notEqual(result.status,0);assert.equal(result.stderr.includes('MISSING_PRIVATE_PATH_987'),false);assert.deepEqual(JSON.parse(result.stderr),{failed:true,code:'io_ENOENT'});
});
test('oversized alignment work and invalid intervals fail before unbounded processing',()=>{
  assert.throws(()=>editCounts(Array(3000).fill('a'),Array(3000).fill('a')),/segment/);
  assert.throws(()=>speechIntervalMetrics([[0,1001]],[],1000),/invalid_interval/);
});
