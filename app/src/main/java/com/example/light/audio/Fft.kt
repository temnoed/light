package com.example.light.audio

/**
 * Итеративное БПФ radix-2 (in-place).
 * Размер должен быть степенью двойки.
 */
internal class Fft(private val size: Int) {

    private val levels: Int
    private val cosTable: FloatArray
    private val sinTable: FloatArray
    private val reversed: IntArray

    init {
        require(size > 1 && size and (size - 1) == 0) { "FFT size must be a power of two" }
        levels = 31 - Integer.numberOfLeadingZeros(size)
        cosTable = FloatArray(size / 2) { Math.cos(2.0 * Math.PI * it / size).toFloat() }
        sinTable = FloatArray(size / 2) { Math.sin(2.0 * Math.PI * it / size).toFloat() }
        reversed = IntArray(size) { Integer.reverse(it) ushr (32 - levels) }
    }

    fun transform(re: FloatArray, im: FloatArray) {
        for (i in 0 until size) {
            val j = reversed[i]
            if (j > i) {
                var tmp = re[i]
                re[i] = re[j]
                re[j] = tmp
                tmp = im[i]
                im[i] = im[j]
                im[j] = tmp
            }
        }

        var span = 2
        while (span <= size) {
            val half = span / 2
            val step = size / span
            var start = 0
            while (start < size) {
                var k = 0
                for (i in start until start + half) {
                    val c = cosTable[k]
                    val s = sinTable[k]
                    val j = i + half
                    val tre = re[j] * c - im[j] * s
                    val tim = re[j] * s + im[j] * c
                    re[j] = re[i] - tre
                    im[j] = im[i] - tim
                    re[i] += tre
                    im[i] += tim
                    k += step
                }
                start += span
            }
            span *= 2
        }
    }
}
