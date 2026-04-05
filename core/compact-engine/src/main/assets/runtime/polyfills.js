/**
 * Polyfills for QuickJS — Web APIs that don't exist in QuickJS
 * but are used by compact-runtime.
 */

// TextEncoder/TextDecoder — used for string↔UTF-8 byte conversion
if (typeof globalThis.TextEncoder === 'undefined') {
    globalThis.TextEncoder = class TextEncoder {
        encode(str) {
            const arr = [];
            for (let i = 0; i < str.length; i++) {
                let c = str.charCodeAt(i);
                if (c < 0x80) {
                    arr.push(c);
                } else if (c < 0x800) {
                    arr.push(0xc0 | (c >> 6), 0x80 | (c & 0x3f));
                } else if (c < 0xd800 || c >= 0xe000) {
                    arr.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f));
                } else {
                    // surrogate pair
                    i++;
                    c = 0x10000 + (((c & 0x3ff) << 10) | (str.charCodeAt(i) & 0x3ff));
                    arr.push(
                        0xf0 | (c >> 18),
                        0x80 | ((c >> 12) & 0x3f),
                        0x80 | ((c >> 6) & 0x3f),
                        0x80 | (c & 0x3f)
                    );
                }
            }
            return new Uint8Array(arr);
        }
    };
}

if (typeof globalThis.TextDecoder === 'undefined') {
    globalThis.TextDecoder = class TextDecoder {
        decode(bytes) {
            const arr = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
            let str = '';
            for (let i = 0; i < arr.length; ) {
                let c = arr[i++];
                if (c < 0x80) {
                    str += String.fromCharCode(c);
                } else if (c < 0xe0) {
                    str += String.fromCharCode(((c & 0x1f) << 6) | (arr[i++] & 0x3f));
                } else if (c < 0xf0) {
                    str += String.fromCharCode(((c & 0x0f) << 12) | ((arr[i++] & 0x3f) << 6) | (arr[i++] & 0x3f));
                } else {
                    const cp = ((c & 0x07) << 18) | ((arr[i++] & 0x3f) << 12) | ((arr[i++] & 0x3f) << 6) | (arr[i++] & 0x3f);
                    str += String.fromCodePoint(cp);
                }
            }
            return str;
        }
    };
}
