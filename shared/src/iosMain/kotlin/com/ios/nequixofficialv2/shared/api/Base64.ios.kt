package com.ios.nequixofficialv2.shared.api

import platform.Foundation.NSData
import platform.Foundation.NSDataBase64DecodingOptions
import platform.Foundation.create
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.usePinned

/**
 * Implementación iOS de decodificación base64
 */
actual fun decodeBase64(input: String): ByteArray {
    val nsData = NSData.create(base64EncodedString = input, options = NSDataBase64DecodingOptions.MIN_VALUE)
        ?: throw IllegalArgumentException("Invalid base64 string")
    
    val length = nsData.length.toInt()
    val bytes = ByteArray(length)
    
    bytes.usePinned { pinned ->
        val pointer = pinned.addressOf(0) as platform.darwin.UnsafeMutablePointer<ByteVar>
        nsData.getBytes(pointer, length = nsData.length)
    }
    
    return bytes
}
