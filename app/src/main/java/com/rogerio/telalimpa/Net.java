package com.rogerio.telalimpa;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Acesso mínimo à internet, sem bibliotecas. */
final class Net {

    private Net() {
    }

    static String get(String url) throws IOException {
        HttpURLConnection c = open(url);
        try {
            InputStream in = c.getInputStream();
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            } finally {
                in.close();
            }
        } finally {
            c.disconnect();
        }
    }

    static void download(String url, File dest) throws IOException {
        File tmp = new File(dest.getPath() + ".tmp");
        HttpURLConnection c = open(url);
        try {
            InputStream in = c.getInputStream();
            try {
                FileOutputStream out = new FileOutputStream(tmp);
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
        } finally {
            c.disconnect();
        }
        if (!tmp.renameTo(dest)) {
            tmp.delete();
            throw new IOException("Não foi possível salvar " + dest);
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "TelaLimpa/2.0");
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new IOException("HTTP " + code);
        }
        return c;
    }
}
