package com.user.localagent.ui

/**
 * Small Markdown subset for assistant answers: headings, bold/italic, inline code, bullet and
 * numbered lists, quotes, rules, fenced code and pipe tables. Pure Kotlin so it is unit tested
 * without Compose; it only changes presentation, never the saved or copied answer text.
 */
internal object MarkdownLite {
    data class Span(val text:String,val bold:Boolean=false,val italic:Boolean=false,val code:Boolean=false)

    sealed interface Block {
        data class Heading(val level:Int,val spans:List<Span>):Block
        data class Paragraph(val spans:List<Span>):Block
        data class ListItem(val marker:String,val depth:Int,val spans:List<Span>):Block
        data class Quote(val spans:List<Span>):Block
        data class Code(val text:String):Block
        data class Table(val rows:List<List<List<Span>>>):Block
        data object Rule:Block
    }

    private val heading=Regex("^(#{1,6})\\s+(.*)$")
    private val bullet=Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val numbered=Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")
    private val rule=Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val tableDivider=Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    fun parse(text:String):List<Block> {
        val lines=text.replace("\r\n","\n").split('\n')
        val blocks=ArrayList<Block>()
        val paragraph=StringBuilder()
        fun flush(){ if(paragraph.isNotEmpty()){blocks+=Block.Paragraph(inline(paragraph.toString()));paragraph.clear()} }
        var i=0
        while(i<lines.size){
            val line=lines[i]
            val trimmed=line.trim()
            when {
                trimmed.startsWith("```")->{
                    flush()
                    val body=ArrayList<String>()
                    i++
                    while(i<lines.size&&!lines[i].trim().startsWith("```")){body+=lines[i];i++}
                    blocks+=Block.Code(body.joinToString("\n"))
                }
                trimmed.isEmpty()->flush()
                rule.matches(line)&&paragraph.isEmpty()->{flush();blocks+=Block.Rule}
                heading.matches(trimmed)->{flush();val m=heading.find(trimmed)!!;blocks+=Block.Heading(m.groupValues[1].length,inline(m.groupValues[2].trimEnd('#',' ')))}
                trimmed.startsWith("|")&&i+1<lines.size&&tableDivider.matches(lines[i+1])->{
                    flush()
                    val rows=ArrayList<List<List<Span>>>()
                    rows+=cells(trimmed)
                    i+=2
                    while(i<lines.size&&lines[i].trim().startsWith("|")){rows+=cells(lines[i].trim());i++}
                    blocks+=Block.Table(rows)
                    continue
                }
                bullet.matches(line)->{flush();val m=bullet.find(line)!!;blocks+=Block.ListItem("•",depth(m.groupValues[1]),inline(m.groupValues[2]))}
                numbered.matches(line)->{flush();val m=numbered.find(line)!!;blocks+=Block.ListItem(m.groupValues[2]+".",depth(m.groupValues[1]),inline(m.groupValues[3]))}
                trimmed.startsWith(">")->{flush();blocks+=Block.Quote(inline(trimmed.trimStart('>').trim()))}
                else->{if(paragraph.isNotEmpty())paragraph.append('\n');paragraph.append(line.trimEnd())}
            }
            i++
        }
        flush()
        return blocks
    }

    private fun depth(indent:String)=(indent.replace("\t","    ").length/2).coerceAtMost(4)

    private fun cells(row:String):List<List<Span>> =
        row.trim().removePrefix("|").removeSuffix("|").split('|').map {inline(it.trim())}

    /** Inline emphasis; unmatched markers stay as literal text so nothing is lost. */
    fun inline(text:String):List<Span> {
        val out=ArrayList<Span>()
        val plain=StringBuilder()
        fun emit(){ if(plain.isNotEmpty()){out+=Span(plain.toString());plain.clear()} }
        var i=0
        while(i<text.length){
            val c=text[i]
            if(c=='\\'&&i+1<text.length&&text[i+1] in "*_`#|\\"){plain.append(text[i+1]);i+=2;continue}
            if(c=='`'){
                val end=text.indexOf('`',i+1)
                if(end>i+1){emit();out+=Span(text.substring(i+1,end),code=true);i=end+1;continue}
            }
            val marker=when {
                text.startsWith("***",i)->"***"
                text.startsWith("**",i)->"**"
                text.startsWith("__",i)->"__"
                // Single markers inside words or numbers (2*3*4, snake_case) are literal.
                (c=='*'||c=='_')&&(i==0||!text[i-1].isLetterOrDigit())->c.toString()
                else->null
            }
            if(marker!=null){
                val end=text.indexOf(marker,i+marker.length)
                val inner=if(end>i+marker.length)text.substring(i+marker.length,end)else null
                if(inner!=null&&inner.isNotBlank()&&!inner.first().isWhitespace()&&!inner.last().isWhitespace()){
                    emit()
                    val bold=marker.length>=2
                    val italic=marker.length!=2
                    inline(inner).forEach {out+=it.copy(bold=it.bold||bold,italic=it.italic||italic)}
                    i=end+marker.length
                    continue
                }
            }
            plain.append(c);i++
        }
        emit()
        return out
    }
}
