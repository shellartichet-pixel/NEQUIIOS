package com.ios.nequixofficialv2.shared.api

import platform.Foundation.NSData
import platform.Foundation.NSDataBase64DecodingOptions
import platform.Foundation.create
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.reinterpret

/**
 * Implementación iOS de decodificación base64 usando NSData
 */
@OptIn(ExperimentalForeignApi::class)
actual fun decodeBase64(input: String): ByteArray {
    val nsData = NSData.create(base64EncodedString = input, options = NSDataBase64DecodingOptions.MIN_VALUE)
        ?: throw IllegalArgumentException("Invalid base64 string")
    
    val length = nsData.length.toInt()
    val bytes = ByteArray(length)
    
    // Acceder a los bytes de NSData directamente
    val dataPointer = nsData.bytes
    if (dataPointer != null) {
        val bytePointer = dataPointer.reinterpret<ByteVar>()
        for (i in 0 until length) {
            bytes[i] = bytePointer[i]
        }
    }
    
    return bytes
}
