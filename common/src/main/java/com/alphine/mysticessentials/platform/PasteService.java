package com.alphine.mysticessentials.platform;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public final class PasteService {
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    /** Uploads plain text to mclo.gs and returns the public URL. */
    public static String uploadToMclogs(String text) throws IOException, InterruptedException {
        String body = "content=" + URLEncoder.encode(text, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.mclo.gs/1/log"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        var resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) throw new IOException("HTTP " + resp.statusCode() + ": " + resp.body());
        // Very small JSON – just snip out the url field without a full parser
        String s = resp.body();
        int i = s.indexOf("\"url\"");
        if (i >= 0) {
            int start = s.indexOf('"', i + 5);
            int end = s.indexOf('"', start + 1);
            if (start >= 0 && end > start) return s.substring(start + 1, end);
        }
        throw new IOException("Unexpected response: " + s);
    }

    private PasteService() {}
}
