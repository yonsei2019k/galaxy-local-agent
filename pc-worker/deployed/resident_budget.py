"""Expand only our native child's resident allowance when desktop headroom permits."""
GIB=1024**3
def expanded_limit(previous,available,resident):
    # Reclaiming pages from the same model repeatedly can dominate large-image work.
    # Count its current resident pages as already allocated; keep 2GiB for the desktop.
    candidate=min(6*GIB,max(0,resident+available-2*GIB))//4096*4096
    return candidate if candidate>=previous+256*1024**2 else previous
