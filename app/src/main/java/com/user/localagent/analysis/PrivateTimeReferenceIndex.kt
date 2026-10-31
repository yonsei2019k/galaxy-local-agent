package com.user.localagent.analysis

/** Source adapters emit ordered disjoint byte spans; repeated report lookups must not scan all rows. */
class PrivateTimeReferenceIndex(refs:List<PrivateSourceTimeRef>,private val byteLength:Int) {
    private val refs=refs.toList()
    init {
        require(byteLength>=0)
        var previousEnd=0
        this.refs.forEach {ref->
            require(ref.startByte>=previousEnd&&ref.endByte>ref.startByte&&ref.endByte<=byteLength)
            previousEnd=ref.endByte
        }
    }
    fun overlapping(start:Int,end:Int,limit:Int):List<PrivateSourceTimeRef> {
        require(start>=0&&end in start..byteLength&&limit in 1..32)
        if(start==end||refs.isEmpty())return emptyList()
        var low=0;var high=refs.size
        while(low<high){val mid=low+(high-low)/2;if(refs[mid].endByte<=start)low=mid+1 else high=mid}
        val selected=ArrayList<PrivateSourceTimeRef>(minOf(limit,refs.size-low))
        while(low<refs.size&&selected.size<limit&&refs[low].startByte<end){selected+=refs[low++ ]}
        return selected
    }
}
