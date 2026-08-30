package com.fluxplayer.app.core.common

/**
 * 自然排序比较器：数字段按数值比较，避免字典序下 "第10章" 排在 "第2章" 之前的问题。
 */
object NaturalOrder {

    val fileNameComparator: Comparator<String> = Comparator { a, b ->
        var ia = 0
        var ib = 0
        while (ia < a.length && ib < b.length) {
            val ca = a[ia]
            val cb = b[ib]
            if (ca.isDigit() && cb.isDigit()) {
                var ja = ia
                while (ja < a.length && a[ja].isDigit()) ja++
                var jb = ib
                while (jb < b.length && b[jb].isDigit()) jb++
                val na = a.substring(ia, ja).toLongOrNull() ?: 0L
                val nb = b.substring(ib, jb).toLongOrNull() ?: 0L
                if (na != nb) return@Comparator na.compareTo(nb)
                ia = ja
                ib = jb
            } else {
                if (ca != cb) return@Comparator ca.compareTo(cb)
                ia++
                ib++
            }
        }
        (a.length - ia).compareTo(b.length - ib)
    }
}

/** 按文件名的自然顺序排序文件列表。 */
fun List<java.io.File>.sortedByNaturalName(): List<java.io.File> =
    sortedWith(compareBy(NaturalOrder.fileNameComparator) { it.name })
