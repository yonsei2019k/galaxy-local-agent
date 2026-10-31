package com.user.localagent.office

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.StringReader
import java.net.URI
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

data class OfficeDocument(val text:String,val paragraphs:List<String>,val limitations:List<String>,val truncated:Boolean=false)
data class OfficeFormula(val reference:String,val expression:String,val cachedValue:String?)
data class OfficeSheet(val name:String,val rows:List<List<String>>,val formulas:List<OfficeFormula> = emptyList(),val truncated:Boolean=false)
data class OfficeWorkbook(val sheets:List<OfficeSheet>,val limitations:List<String>,val truncated:Boolean=false)
data class OfficeExport(val file:File,val mimeType:String,val format:String,val limitations:List<String>)

/** SDK-only OOXML. The host supplies streams explicitly selected by the user through SAF.
 * No macros, formula evaluation, network access or arbitrary file extraction. HTTP(S) hyperlink
 * targets are ignored; external workbook/template/OLE relationships remain unsupported.
 * Input streams are consumed and closed. Export files are unique, private, and never overwrite an input.
 */
class NativeOfficeTools(private val exportDirectory:File) {
    constructor(context:Context):this(File(context.applicationContext.filesDir,"office-exports"))

    suspend fun readDocx(input:InputStream):OfficeDocument=withContext(Dispatchers.IO) {
        val files=readPackage(input);requireMain(files,"word/document.xml",DOCX_MAIN)
        val document=xml(files.getValue("word/document.xml"));val body=document.getElementsByTagNameNS(WORD,"body").item(0)
            ?:error("DOCX 본문이 없습니다.")
        val paragraphs=mutableListOf<String>();var length=0;var truncated=false
        val nodes=(body as Element).getElementsByTagNameNS(WORD,"p")
        for(index in 0 until nodes.length){
            currentCoroutineContext().ensureActive()
            if(paragraphs.size>=10_000||length>=MAX_DOCUMENT_CHARS){truncated=true;break}
            val text=wordText(nodes.item(index));val available=(MAX_DOCUMENT_CHARS-length-if(paragraphs.isEmpty())0 else 1).coerceAtLeast(0)
            val kept=clip(text,available);if(kept.length<text.length)truncated=true
            paragraphs+=kept;length+=kept.length+if(paragraphs.size>1)1 else 0
        }
        OfficeDocument(paragraphs.joinToString("\n"),paragraphs,DOCX_LIMITS,truncated)
    }

    suspend fun readXlsx(input:InputStream):OfficeWorkbook=withContext(Dispatchers.IO) {
        val files=readPackage(input);requireMain(files,"xl/workbook.xml",XLSX_MAIN)
        val workbook=xml(files.getValue("xl/workbook.xml"));val relationships=relationships(files,"xl/_rels/workbook.xml.rels")
        val shared=files["xl/sharedStrings.xml"]?.let {bytes->
            val nodes=xml(bytes).getElementsByTagNameNS(SHEET,"si");require(nodes.length<=MAX_CELLS){"공유 문자열이 너무 많습니다."}
            (0 until nodes.length).map {index->richText(nodes.item(index)).also {require(it.length<=32_767)}}
        }.orEmpty()
        val nodes=workbook.getElementsByTagNameNS(SHEET,"sheet");val sheets=mutableListOf<OfficeSheet>()
        var truncated=nodes.length>MAX_SHEETS;var cells=0;var textChars=0;var formulaChars=0
        for(index in 0 until minOf(nodes.length,MAX_SHEETS)){
            currentCoroutineContext().ensureActive()
            val sheet=nodes.item(index)as Element;val name=sheet.getAttribute("name")
            require(name.isNotBlank()&&name.length<=128){"시트 이름이 유효하지 않습니다."}
            val id=sheet.getAttributeNS(DOCUMENT_RELS,"id")
            val path=relationships[id]?.takeIf {it.second.endsWith("/worksheet")}?.first?:error("시트 연결 정보가 없습니다.")
            val root=xml(files[path]?:error("시트 XML이 없습니다."));val cellNodes=root.getElementsByTagNameNS(SHEET,"c")
            val rows=linkedMapOf<Int,MutableMap<Int,String>>();val formulas=mutableListOf<OfficeFormula>();var sheetTruncated=false
            val rowNodes=root.getElementsByTagNameNS(SHEET,"row")
            for(rowIndex in 0 until rowNodes.length){
                val number=(rowNodes.item(rowIndex)as Element).getAttribute("r").toIntOrNull()?:continue
                require(number in 1..1_048_576)
                if(number<=MAX_ROWS)rows.getOrPut(number-1){linkedMapOf()}else sheetTruncated=true
            }
            for(cellIndex in 0 until cellNodes.length){
                currentCoroutineContext().ensureActive()
                val cell=cellNodes.item(cellIndex)as Element;val reference=cell.getAttribute("r")
                val coordinate=coordinate(reference)
                if(coordinate.first>=MAX_ROWS||coordinate.second>=MAX_COLUMNS||cells>=MAX_CELLS||textChars>=MAX_TABLE_CHARS){sheetTruncated=true;continue}
                val raw=directChild(cell,"v")?.textContent
                val formula=directChild(cell,"f")?.textContent
                if(formula!=null){require(formula.length<=8192){"수식 길이 상한을 초과했습니다."}
                    if(formulaChars+formula.length<=MAX_DOCUMENT_CHARS){formulas+=OfficeFormula(reference,formula,raw);formulaChars+=formula.length}else sheetTruncated=true}
                val value=when(cell.getAttribute("t")){
                    "s"->{val sharedIndex=raw?.toIntOrNull()?:error("공유 문자열 인덱스 오류");shared.getOrNull(sharedIndex)?:error("공유 문자열 범위 오류")}
                    "inlineStr"->directChild(cell,"is")?.let(::richText).orEmpty()
                    "b"->when(raw){"1"->"TRUE";"0"->"FALSE";null->"";else->error("잘못된 논리 셀 값")}
                    "","n","str","d","e"->raw.orEmpty()
                    else->error("지원하지 않는 셀 형식")
                }
                require(value.length<=32_767){"셀 텍스트 길이 상한을 초과했습니다."}
                val kept=clip(value,(MAX_TABLE_CHARS-textChars).coerceAtLeast(0));if(kept.length<value.length)sheetTruncated=true
                val row=rows.getOrPut(coordinate.first){linkedMapOf()};require(coordinate.second !in row){"중복 셀 주소가 있습니다."}
                row[coordinate.second]=kept;cells++;textChars+=kept.length
            }
            val values=if(rows.isEmpty())emptyList()else (0..rows.keys.max()).map {rowIndex->
                rows[rowIndex]?.let {row->row.keys.maxOrNull()?.let {last->(0..last).map {column->row[column].orEmpty()}}?:emptyList()}?:emptyList()
            }
            sheets+=OfficeSheet(name,values,formulas,sheetTruncated);truncated=truncated||sheetTruncated
        }
        OfficeWorkbook(sheets,XLSX_LIMITS,truncated)
    }

    suspend fun writeDocx(title:String,paragraphs:List<String>):OfficeExport=withContext(Dispatchers.IO) {
        require(title.length<=200&&paragraphs.size<=10_000);checkText(title)
        require(paragraphs.sumOf {it.length.toLong()+1}+title.length<=MAX_DOCUMENT_CHARS)
        paragraphs.forEach(::checkText)
        val body=buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document xmlns:w=\"$WORD\"><w:body>")
            (if(title.isBlank())paragraphs else listOf(title)+paragraphs).forEach {paragraph->
                append("<w:p><w:r><w:t xml:space=\"preserve\">")
                append(escape(paragraph).replace("\t","</w:t><w:tab/><w:t xml:space=\"preserve\">").replace("\n","</w:t><w:br/><w:t xml:space=\"preserve\">"))
                append("</w:t></w:r></w:p>")
            }
            append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\"/></w:sectPr></w:body></w:document>")
        }
        val parts=linkedMapOf("[Content_Types].xml" to contentTypes(mapOf("word/document.xml" to DOCX_MAIN)),
            "_rels/.rels" to relXml(listOf(Triple("rId1",OFFICE_DOCUMENT,"word/document.xml"))),"word/document.xml" to body)
        val file=export("docx",parts)
        try {readDocx(file.inputStream());OfficeExport(file,DOCX_MIME,"docx",DOCX_LIMITS)}catch(error:Throwable){file.delete();throw error}
    }

    /** Literal strings only. Source formulas are flattened to their existing displayed/cached values. */
    suspend fun writeXlsx(sheets:List<OfficeSheet>):OfficeExport=withContext(Dispatchers.IO) {
        require(sheets.size in 1..MAX_SHEETS);require(sheets.map {it.name.lowercase(Locale.ROOT)}.distinct().size==sheets.size)
        var cells=0L;var characters=0L
        sheets.forEach {sheet->
            require(sheet.name.length in 1..31&&sheet.name.none {it in "[]:*?/\\"||it.code<32}&&!sheet.name.startsWith("'")&&!sheet.name.endsWith("'"));checkText(sheet.name)
            require(sheet.rows.size<=MAX_ROWS)
            sheet.rows.forEach {row->require(row.size<=MAX_COLUMNS);cells+=row.size;row.forEach {value->require(value.length<=32_767);checkText(value);characters+=value.length}}
        }
        require(cells<=MAX_CELLS&&characters<=MAX_TABLE_CHARS)
        val parts=linkedMapOf<String,String>();val overrides=linkedMapOf("xl/workbook.xml" to XLSX_MAIN)
        sheets.forEachIndexed {index,sheet->
            val path="xl/worksheets/sheet${index+1}.xml";overrides[path]=WORKSHEET_MIME
            parts[path]=buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"$SHEET\"><sheetData>")
                sheet.rows.forEachIndexed {rowIndex,row->
                    append("<row r=\"${rowIndex+1}\">")
                    row.forEachIndexed {column,value->append("<c r=\"${columnName(column)}${rowIndex+1}\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${escape(value)}</t></is></c>")}
                    append("</row>")
                };append("</sheetData></worksheet>")
            }
        }
        parts["[Content_Types].xml"]=contentTypes(overrides)
        parts["_rels/.rels"]=relXml(listOf(Triple("rId1",OFFICE_DOCUMENT,"xl/workbook.xml")))
        parts["xl/workbook.xml"]="<?xml version=\"1.0\" encoding=\"UTF-8\"?><workbook xmlns=\"$SHEET\" xmlns:r=\"$DOCUMENT_RELS\"><sheets>"+
            sheets.mapIndexed {index,sheet->"<sheet name=\"${escape(sheet.name)}\" sheetId=\"${index+1}\" r:id=\"rId${index+1}\"/>"}.joinToString("")+"</sheets></workbook>"
        parts["xl/_rels/workbook.xml.rels"]=relXml(sheets.indices.map {index->Triple("rId${index+1}","$DOCUMENT_RELS/worksheet","worksheets/sheet${index+1}.xml")})
        val file=export("xlsx",parts)
        try {readXlsx(file.inputStream());OfficeExport(file,XLSX_MIME,"xlsx",XLSX_LIMITS)}catch(error:Throwable){file.delete();throw error}
    }

    private suspend fun readPackage(input:InputStream):Map<String,ByteArray> {
        val files=linkedMapOf<String,ByteArray>();val names=mutableSetOf<String>();var total=0L;var entries=0
        ZipInputStream(BoundedInput(input,24L*1024*1024)).use {zip->
            while(true){
                currentCoroutineContext().ensureActive();val entry=zip.nextEntry?:break
                require(++entries<=2000){"ZIP 항목은 최대 2,000개입니다."};safeName(entry.name)
                val lower=entry.name.lowercase(Locale.ROOT);require(names.add(lower)){"중복 ZIP 경로입니다."}
                require(!lower.endsWith(".bin")&&!lower.contains("/embeddings/")&&!lower.contains("/activex/")&&!lower.contains("/externallinks/")&&
                    !Regex("\\.(?:exe|dll|com|vbs|js|jar|apk|vba)$").containsMatchIn(lower)){"매크로·실행 파일·외부 연결·포함 개체는 지원하지 않습니다."}
                require(entry.size<=MAX_ZIP_BYTES){"ZIP 항목 용량 상한 초과"}
                val output=ByteArrayOutputStream();val buffer=ByteArray(16*1024)
                while(true){currentCoroutineContext().ensureActive();val count=zip.read(buffer);if(count<0)break;total+=count;require(total<=MAX_ZIP_BYTES){"압축 해제 총량은 20 MiB 이하입니다."};output.write(buffer,0,count)}
                if(!entry.isDirectory)files[entry.name]=output.toByteArray();zip.closeEntry()
            }
        }
        require(files.isNotEmpty()){ "읽을 수 있는 OOXML ZIP 파일이 아닙니다." }
        files.filterKeys {it.endsWith(".rels",true)}.forEach {(path,_)->relationships(files,path)}
        val types=xml(files["[Content_Types].xml"]?:error("OOXML 콘텐츠 형식 정보가 없습니다."))
        val nodes=types.getElementsByTagNameNS(CONTENT_TYPES,"*")
        for(index in 0 until nodes.length){val type=(nodes.item(index)as Element).getAttribute("ContentType").lowercase(Locale.ROOT);require(!type.contains("macroenabled")&&!type.contains("vbaproject")&&!type.contains("activex")){"매크로 콘텐츠는 지원하지 않습니다."}}
        return files
    }

    private fun requireMain(files:Map<String,ByteArray>,path:String,type:String) {
        require(path in files)
        val types=xml(files.getValue("[Content_Types].xml")).getElementsByTagNameNS(CONTENT_TYPES,"Override")
        require((0 until types.length).any {index->val item=types.item(index)as Element;item.getAttribute("PartName")=="/$path"&&item.getAttribute("ContentType")==type}){"지원하는 DOCX/XLSX 형식이 아닙니다."}
        require(relationships(files,"_rels/.rels").values.any {it.first==path&&it.second==OFFICE_DOCUMENT}){"OOXML 본문 연결이 없습니다."}
    }
    private fun relationships(files:Map<String,ByteArray>,path:String):Map<String,Pair<String,String>> {
        val bytes=files[path]?:return emptyMap();val result=linkedMapOf<String,Pair<String,String>>();val seen=mutableSetOf<String>()
        val nodes=xml(bytes).getElementsByTagNameNS(PACKAGE_RELS,"Relationship")
        val base=if(path=="_rels/.rels")""else path.substringBefore("/_rels/")+"/"
        for(index in 0 until nodes.length){
            val node=nodes.item(index)as Element;val mode=node.getAttribute("TargetMode")
            val id=node.getAttribute("Id");require(id.isNotBlank()&&seen.add(id))
            val type=node.getAttribute("Type");val target=node.getAttribute("Target")
            if(mode.equals("External",true)){
                require(type=="$DOCUMENT_RELS/hyperlink"){"외부 통합문서·템플릿·포함 개체 연결은 지원하지 않습니다."}
                val uri=URI(target)
                require(uri.scheme?.lowercase(Locale.ROOT) in setOf("http","https")&&!uri.rawAuthority.isNullOrBlank()&&!uri.isOpaque){"HTTP(S) 하이퍼링크만 표시 텍스트로 읽을 수 있습니다."}
                // Never return, resolve, fetch, activate or copy an external target into an output package.
                continue
            }
            require(mode.isEmpty()||mode.equals("Internal",true)){"잘못된 문서 관계 형식입니다."}
            require(target.isNotBlank()&&!target.startsWith('/')&&!target.contains(':')&&!target.contains('\\')&&!target.contains('?')&&!target.contains('#')){"잘못된 내부 문서 연결입니다."}
            val parts=mutableListOf<String>()
            (base+target).split('/').forEach {segment->when(segment){"","."->Unit;".."->{require(parts.isNotEmpty());parts.removeAt(parts.lastIndex)};else->parts+=segment}}
            val normalized=parts.joinToString("/");safeName(normalized)
            result[id]=normalized to type
        }
        return result
    }
    private fun xml(bytes:ByteArray):org.w3c.dom.Document {
        require(bytes.size<=5*1024*1024){"개별 XML은 5 MiB 이하입니다."}
        val decoder=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val text=decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        require('\u0000' !in text&&!Regex("(?i)<!\\s*(?:DOCTYPE|ENTITY)").containsMatchIn(text)){"DTD와 외부 엔터티는 허용하지 않습니다."}
        Regex("(?i)<\\?xml[^>]*encoding\\s*=\\s*['\"]([^'\"]+)").find(text)?.groupValues?.get(1)?.let {require(it.equals("UTF-8",true)||it.equals("UTF8",true)){"UTF-8 XML만 지원합니다."}}
        val factory=DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware=true;isExpandEntityReferences=false;isValidating=false
            runCatching {setXIncludeAware(false)}
            listOf(XMLConstants.FEATURE_SECURE_PROCESSING to true,"http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://xml.org/sax/features/external-general-entities" to false,"http://xml.org/sax/features/external-parameter-entities" to false,
                "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false).forEach {(feature,value)->runCatching {setFeature(feature,value)}}
            runCatching {setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD","")};runCatching {setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema","")}
        }
        val builder=factory.newDocumentBuilder();builder.setEntityResolver {_,_->throw SAXException("External entities disabled")}
        val document=builder.parse(InputSource(StringReader(text)))
        val queue=ArrayDeque<Pair<Node,Int>>();queue.add(document to 0);var count=0
        while(queue.isNotEmpty()){val(node,depth)=queue.removeLast();require(++count<=200_000&&depth<=64){"XML 구조 상한 초과"};for(index in 0 until node.childNodes.length)queue.add(node.childNodes.item(index)to depth+1)}
        return document
    }
    private fun wordText(node:Node):String=buildString {
        fun visit(current:Node){when {current.namespaceURI==WORD&&current.localName=="t"->append(current.textContent)
            current.namespaceURI==WORD&&current.localName in setOf("br","cr")->append('\n')
            current.namespaceURI==WORD&&current.localName=="tab"->append('\t')
            else->for(index in 0 until current.childNodes.length)visit(current.childNodes.item(index))}}
        visit(node)
    }
    private fun richText(node:Node):String=buildString {
        fun visit(current:Node){if(current.namespaceURI==SHEET&&current.localName=="rPh")return
            if(current.namespaceURI==SHEET&&current.localName=="t")append(current.textContent)
            else for(index in 0 until current.childNodes.length)visit(current.childNodes.item(index))}
        visit(node)
    }
    private fun directChild(node:Node,name:String):Element?=(0 until node.childNodes.length).map {node.childNodes.item(it)}.filterIsInstance<Element>().firstOrNull {it.namespaceURI==SHEET&&it.localName==name}
    private fun coordinate(reference:String):Pair<Int,Int> {
        val match=Regex("([A-Z]{1,3})([1-9][0-9]{0,6})").matchEntire(reference)?:error("잘못된 셀 주소")
        var column=0;match.groupValues[1].forEach {column=column*26+(it-'A'+1)};val row=match.groupValues[2].toInt()
        require(row<=1_048_576&&column<=16_384);return row-1 to column-1
    }
    private fun columnName(index:Int):String {var value=index+1;var result="";while(value>0){value--;result=('A'+value%26)+result;value/=26};return result}
    private fun safeName(name:String){require(name.length in 1..300&&!name.startsWith('/')&&!name.contains('\\')&&!name.contains(':')&&name.none {it.code<32}&&name.trimEnd('/').split('/').none {it.isEmpty()||it=="."||it==".."}){"안전하지 않은 ZIP 경로입니다."}}
    private fun checkText(value:String){var index=0;while(index<value.length){val point=value.codePointAt(index);require(point==9||point==10||point==13||point in 0x20..0xD7FF||point in 0xE000..0xFFFD||point in 0x10000..0x10FFFF){"XML에 저장할 수 없는 문자가 있습니다."};index+=Character.charCount(point)}}
    private fun escape(value:String):String=value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;").replace("\r","&#13;")
    private fun clip(value:String,limit:Int):String {var end=minOf(value.length,limit);if(end>0&&end<value.length&&value[end-1].isHighSurrogate()&&value[end].isLowSurrogate())end--;return value.substring(0,end)}
    private fun contentTypes(parts:Map<String,String>)="<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"$CONTENT_TYPES\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>"+parts.entries.joinToString(""){"<Override PartName=\"/${escape(it.key)}\" ContentType=\"${escape(it.value)}\"/>"}+"</Types>"
    private fun relXml(items:List<Triple<String,String,String>>)="<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"$PACKAGE_RELS\">"+items.joinToString(""){(id,type,target)->"<Relationship Id=\"${escape(id)}\" Type=\"${escape(type)}\" Target=\"${escape(target)}\"/>"}+"</Relationships>"
    private suspend fun export(extension:String,parts:Map<String,String>):File {
        require(exportDirectory.isDirectory||exportDirectory.mkdirs())
        val directory=exportDirectory.canonicalFile;val name="ziphap-${UUID.randomUUID()}.$extension";val target=File(directory,name)
        val temporary=File.createTempFile("office-",".part",directory)
        try {ZipOutputStream(temporary.outputStream()).use {zip->
            var total=0L;for((path,text)in parts){currentCoroutineContext().ensureActive();safeName(path);val bytes=text.toByteArray(Charsets.UTF_8);total+=bytes.size;require(total<=MAX_ZIP_BYTES);zip.putNextEntry(ZipEntry(path));zip.write(bytes);zip.closeEntry()}
        };require(!target.exists()&&temporary.renameTo(target));return target}
        catch(error:Throwable){temporary.delete();throw error}
    }
    private class BoundedInput(input:InputStream,private val maximum:Long):FilterInputStream(input){
        private var count=0L
        override fun read():Int {val result=super.read();if(result>=0)checkCount(1);return result}
        override fun read(buffer:ByteArray,offset:Int,length:Int):Int {val result=`in`.read(buffer,offset,length);if(result>0)checkCount(result);return result}
        private fun checkCount(added:Int){count+=added;require(count<=maximum){"압축 파일 입력 용량 상한 초과"}}
    }
    companion object {
        const val DOCX_MIME="application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        const val XLSX_MIME="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        private const val MAX_ZIP_BYTES=20L*1024*1024
        private const val MAX_DOCUMENT_CHARS=200_000
        private const val MAX_TABLE_CHARS=1_000_000
        private const val MAX_ROWS=5000
        private const val MAX_COLUMNS=256
        private const val MAX_CELLS=100_000
        private const val MAX_SHEETS=8
        private const val WORD="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        private const val SHEET="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        private const val DOCUMENT_RELS="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        private const val PACKAGE_RELS="http://schemas.openxmlformats.org/package/2006/relationships"
        private const val CONTENT_TYPES="http://schemas.openxmlformats.org/package/2006/content-types"
        private const val OFFICE_DOCUMENT="$DOCUMENT_RELS/officeDocument"
        private const val DOCX_MAIN="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
        private const val XLSX_MAIN="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"
        private const val WORKSHEET_MIME="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"
        private val DOCX_LIMITS=listOf("본문 문단·표 안의 문단 텍스트만 추출합니다. 서식·페이지 배치·이미지·머리글·각주·변경 이력은 재현하지 않습니다.","최대 20 MiB 압축 해제, 2,000개 ZIP 항목, UTF-8 XML, 본문 200,000자입니다. HTTP(S) 하이퍼링크는 열지 않고 표시 텍스트만 읽습니다. 매크로·외부 통합문서·템플릿·포함 개체 연결은 거부합니다.")
        private val XLSX_LIMITS=listOf("셀 값과 수식의 저장된 캐시만 읽습니다. 수식을 계산하지 않으며 캐시가 없으면 결과는 빈 값입니다.","날짜·통화·숫자 서식을 적용하지 않습니다. 날짜 일련번호는 원래 숫자 값입니다. 차트·병합·서식·매크로는 재현하지 않습니다. HTTP(S) 하이퍼링크는 열거나 출력에 보존하지 않습니다.","최대 8개 시트, 시트별 5,000행·256열, 총 100,000셀·1,000,000자입니다. 출력은 문자열 값만 저장하며 원본 수식은 캐시 값으로 평탄화합니다.")
    }
}
