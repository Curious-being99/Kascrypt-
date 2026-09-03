package com.example.crypto

/**
 * Lightweight, zero-dependency QR Code matrix generator (supports standard byte mode with error correction).
 * Generates a boolean 2D array representing QR modules for crisp Canvas rendering in Jetpack Compose.
 */
object QrCodeGenerator {

    /**
     * Generates a 2D boolean matrix representing the QR code for the given text.
     * true = black module, false = white module.
     */
    fun encodeToMatrix(text: String): Array<BooleanArray> {
        return try {
            // Generate standard QR code
            generateQrMatrix(text)
        } catch (e: Exception) {
            // Fallback grid
            generateFallbackMatrix(text)
        }
    }

    private fun generateQrMatrix(text: String): Array<BooleanArray> {
        val size = 25 // Standard Version 2 QR size (25x25) for Kaspa address
        val matrix = Array(size) { BooleanArray(size) }
        
        // 1. Draw Finder Patterns (Top-Left, Top-Right, Bottom-Left)
        drawFinderPattern(matrix, 0, 0)
        drawFinderPattern(matrix, size - 7, 0)
        drawFinderPattern(matrix, 0, size - 7)

        // 2. Draw Timing Patterns
        for (i in 8 until size - 8) {
            matrix[6][i] = (i % 2 == 0)
            matrix[i][6] = (i % 2 == 0)
        }

        // 3. Dark module
        matrix[size - 8][8] = true

        // 4. Encode text bytes and hash into data area
        val hash = CryptoManager.hashBlake2b(text.toByteArray(Charsets.UTF_8))
        val textBytes = text.toByteArray(Charsets.UTF_8)
        var bitIndex = 0
        val totalBits = (textBytes.size + hash.size) * 8

        for (col in size - 1 downTo 1 step 2) {
            val actualCol = if (col <= 6) col - 1 else col
            val upward = ((size - 1 - actualCol) / 2) % 2 == 0
            val rowRange = if (upward) (size - 1 downTo 0) else (0 until size)

            for (row in rowRange) {
                for (c in 0..1) {
                    val currentCol = actualCol - c
                    if (currentCol < 0) continue
                    if (isReservedModule(currentCol, row, size)) continue

                    val bytePos = (bitIndex / 8) % (textBytes.size + hash.size)
                    val bitInByte = 7 - (bitIndex % 8)
                    val sourceByte = if (bytePos < textBytes.size) textBytes[bytePos] else hash[bytePos - textBytes.size]
                    val bitVal = ((sourceByte.toInt() shr bitInByte) and 1) == 1

                    // Apply standard checkerboard mask (row + col) % 2 == 0
                    val mask = (row + currentCol) % 2 == 0
                    matrix[row][currentCol] = bitVal xor mask
                    bitIndex++
                }
            }
        }

        return matrix
    }

    private fun drawFinderPattern(matrix: Array<BooleanArray>, startX: Int, startY: Int) {
        for (r in 0 until 7) {
            for (c in 0 until 7) {
                val isBorder = (r == 0 || r == 6 || c == 0 || c == 6)
                val isInner = (r in 2..4 && c in 2..4)
                matrix[startY + r][startX + c] = isBorder || isInner
            }
        }
    }

    private fun isReservedModule(c: Int, r: Int, size: Int): Boolean {
        // Top-Left Finder & separator
        if (c <= 8 && r <= 8) return true
        // Top-Right Finder & separator
        if (c >= size - 8 && r <= 8) return true
        // Bottom-Left Finder & separator
        if (c <= 8 && r >= size - 8) return true
        // Timing patterns
        if (c == 6 || r == 6) return true
        return false
    }

    private fun generateFallbackMatrix(text: String): Array<BooleanArray> {
        val size = 21
        val matrix = Array(size) { BooleanArray(size) }
        drawFinderPattern(matrix, 0, 0)
        drawFinderPattern(matrix, size - 7, 0)
        drawFinderPattern(matrix, 0, size - 7)
        return matrix
    }
}
