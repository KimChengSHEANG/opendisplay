package com.peetzweg.opendisplay.net

/** Systematic Reed-Solomon coding over GF(256). */
object ReedSolomon {
    private val log = IntArray(256)
    private val exp = IntArray(512)

    init {
        var x = 1
        for (i in 0 until 255) {
            exp[i] = x
            exp[i + 255] = x
            log[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D
        }
    }

    fun encode(dataShards: Array<ByteArray>, parityCount: Int): Array<ByteArray> {
        require(dataShards.isNotEmpty())
        require(parityCount >= 0)
        require(dataShards.size + parityCount <= UdpVideoProtocol.MAX_RS_SHARDS)
        val shardSize = dataShards.first().size
        require(dataShards.all { it.size == shardSize })

        return Array(parityCount) { parityIndex ->
            ByteArray(shardSize).also { parity ->
                for (dataIndex in dataShards.indices) {
                    val coefficient = cauchyCoefficient(dataIndex, dataShards.size + parityIndex)
                    for (byteIndex in 0 until shardSize) {
                        parity[byteIndex] =
                            gfAdd(parity[byteIndex], gfMul(dataShards[dataIndex][byteIndex], coefficient))
                    }
                }
            }
        }
    }

    /**
     * Returns the original data shards, recovering null entries when enough
     * data or parity shards are present. All present shards must be equal-sized.
     */
    fun decode(shardsIncludingNulls: Array<ByteArray?>, dataCount: Int): Array<ByteArray>? {
        require(dataCount > 0)
        require(shardsIncludingNulls.size >= dataCount)
        val shardSize = shardsIncludingNulls.firstOrNull { it != null }?.size ?: return null
        if (shardsIncludingNulls.any { it != null && it.size != shardSize }) return null
        if (shardsIncludingNulls.count { it != null } < dataCount) return null

        if ((0 until dataCount).all { shardsIncludingNulls[it] != null }) {
            return Array(dataCount) { shardsIncludingNulls[it]!! }
        }

        val present = shardsIncludingNulls.indices
            .filter { shardsIncludingNulls[it] != null }
            .take(dataCount)
        val matrix = Array(dataCount) { row ->
            IntArray(dataCount).also { coefficients ->
                val shardIndex = present[row]
                if (shardIndex < dataCount) {
                    coefficients[shardIndex] = 1
                } else {
                    val parityIndex = shardIndex - dataCount
                    for (column in 0 until dataCount) {
                        coefficients[column] =
                            cauchyCoefficient(column, dataCount + parityIndex).toInt() and 0xFF
                    }
                }
            }
        }
        val inverse = invert(matrix) ?: return null

        return Array(dataCount) { dataIndex ->
            shardsIncludingNulls[dataIndex] ?: ByteArray(shardSize).also { recovered ->
                for (row in 0 until dataCount) {
                    val coefficient = inverse[dataIndex][row].toByte()
                    if (coefficient.toInt() == 0) continue
                    val source = shardsIncludingNulls[present[row]]!!
                    for (byteIndex in 0 until shardSize) {
                        recovered[byteIndex] =
                            gfAdd(recovered[byteIndex], gfMul(source[byteIndex], coefficient))
                    }
                }
            }
        }
    }

    private fun invert(matrix: Array<IntArray>): Array<IntArray>? {
        val size = matrix.size
        val augmented = Array(size) { row ->
            IntArray(size * 2).also {
                for (column in 0 until size) it[column] = matrix[row][column]
                it[size + row] = 1
            }
        }
        for (column in 0 until size) {
            var pivot = column
            while (pivot < size && augmented[pivot][column] == 0) pivot++
            if (pivot == size) return null
            if (pivot != column) {
                val temporary = augmented[column]
                augmented[column] = augmented[pivot]
                augmented[pivot] = temporary
            }

            val scale = gfInverse(augmented[column][column].toByte())
            for (i in augmented[column].indices) {
                augmented[column][i] = gfMul(augmented[column][i].toByte(), scale).toInt() and 0xFF
            }
            for (row in 0 until size) {
                if (row == column) continue
                val factor = augmented[row][column].toByte()
                if (factor.toInt() == 0) continue
                for (i in augmented[row].indices) {
                    augmented[row][i] = gfAdd(
                        augmented[row][i].toByte(),
                        gfMul(factor, augmented[column][i].toByte()),
                    ).toInt() and 0xFF
                }
            }
        }
        return Array(size) { row -> IntArray(size) { column -> augmented[row][size + column] } }
    }

    private fun gfAdd(a: Byte, b: Byte): Byte = (a.toInt() xor b.toInt()).toByte()

    private fun gfMul(a: Byte, b: Byte): Byte {
        val left = a.toInt() and 0xFF
        val right = b.toInt() and 0xFF
        if (left == 0 || right == 0) return 0
        return exp[log[left] + log[right]].toByte()
    }

    private fun gfInverse(value: Byte): Byte {
        val unsigned = value.toInt() and 0xFF
        require(unsigned != 0)
        return exp[255 - log[unsigned]].toByte()
    }

    private fun cauchyCoefficient(dataIndex: Int, parityShardIndex: Int): Byte =
        gfInverse((dataIndex xor parityShardIndex).toByte())
}
