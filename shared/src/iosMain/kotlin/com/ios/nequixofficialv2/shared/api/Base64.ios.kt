package com.ios.nequixofficialv2.shared.api

import platform.Foundation.NSData
import platform.Foundation.NSDataBase64DecodingOptions
import platform.Foundation.create
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.readBytes

/**
 * Implementación iOS de decodificación base64 usando NSData
 * Usa readBytes que es la forma más simple y directa en Kotlin/Native
 */
@OptIn(ExperimentalForeignApi::class)
actual fun decodeBase64(input: String): ByteArray {
    val nsData = NSData.create(base64EncodedString = input, options = NSDataBase64DecodingOptions.MIN_VALUE)
        ?: throw IllegalArgumentException("Invalid base64 string")
    
    // Usar readBytes directamente desde el puntero de NSData
    val dataPointer = nsData.bytes
    if (dataPointer != null) {
        val bytePointer = dataPointer.reinterpret<ByteVar>()
        return bytePointer.readBytes(nsData.length.toInt())
    }
    
    return ByteArray(0)
}
