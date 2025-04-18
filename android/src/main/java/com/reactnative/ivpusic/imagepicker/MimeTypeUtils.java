package com.reactnative.ivpusic.imagepicker;

import android.graphics.Bitmap;

public class MimeTypeUtils {
    public static Bitmap.CompressFormat getBitmapCompressFormat(final String mimeType) {
        if (mimeType.equals("image/png")) {
            return Bitmap.CompressFormat.PNG;
        } else {
            return Bitmap.CompressFormat.JPEG;
        }
    }
}
