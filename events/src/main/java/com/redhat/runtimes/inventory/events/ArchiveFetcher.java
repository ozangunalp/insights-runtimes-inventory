/* Copyright (C) Red Hat 2024 */
package com.redhat.runtimes.inventory.events;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

@ApplicationScoped
public class ArchiveFetcher {

  private HttpClient httpClient;

  public List<String> getJsonsFromArchiveStream(String url) {
    return getJsonsFromArchiveStream(getInputStreamFromS3(url));
  }

  List<String> getJsonsFromArchiveStream(InputStream archiveStream) {
    // The egg file comes in as a String, but it is actually a gzipped tarfile
    // So we will turn it into a stream, 'uncompress' the stream, then walk
    // the archive for files we care about.
    String insightsDataPath = "/data/var/tmp/insights-runtimes/uploads/";
    List<String> jsonFiles = new ArrayList<String>();

    try {
      GzipCompressorInputStream gzis = new GzipCompressorInputStream(archiveStream);
      TarArchiveInputStream tarInput = new TarArchiveInputStream(gzis);

      ArchiveEntry entry;
      while ((entry = tarInput.getNextEntry()) != null) {
        String entryName = entry.getName();

        // Skip any file not in our relevant path
        if (!entryName.contains(insightsDataPath)) {
          continue;
        }

        // Read in the file stream and turn it into a string for processing
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int bytesRead;
        while ((bytesRead = tarInput.read(buffer)) != -1) {
          baos.write(buffer, 0, bytesRead);
        }
        String json = new String(baos.toByteArray());
        if (json == null || json.isEmpty()) {
          continue;
        }

        jsonFiles.add(json);
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    return jsonFiles;
  }

  private InputStream getInputStreamFromS3(String urlStr) {
    try {
      var uri = new URL(urlStr).toURI();
      var requestBuilder = HttpRequest.newBuilder().uri(uri);
      var request = requestBuilder.GET().build();
      Log.debugf("Issuing a HTTP POST request to %s", request);

      if (httpClient == null) {
        httpClient = HttpClient.newBuilder().build();
      }
      var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
      Log.debugf("S3 HTTP Client status: %s", response.statusCode());

      return response.body();
    } catch (URISyntaxException | IOException | InterruptedException e) {
      Log.error("Error in HTTP send: ", e);
      throw new RuntimeException(e);
    }
  }

  void setHttpClient(HttpClient httpClient) {
    this.httpClient = httpClient;
  }

  public String getJsonFromS3(String urlStr) {
    try {
      var uri = new URL(urlStr).toURI();
      var requestBuilder = HttpRequest.newBuilder().uri(uri);
      var request = requestBuilder.GET().build();
      Log.debugf("Issuing a HTTP POST request to %s", request);

      if (httpClient == null) {
        httpClient = HttpClient.newBuilder().build();
      }
      var response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
      Log.debugf("S3 HTTP Client status: %s", response.statusCode());

      return unzipJson(response.body());
    } catch (URISyntaxException | IOException | InterruptedException e) {
      Log.error("Error in HTTP send: ", e);
      throw new RuntimeException(e);
    }
  }

  public static String unzipJson(byte[] buffy) {
    try (var bais = new ByteArrayInputStream(buffy);
        var gunzip = new GZIPInputStream(bais)) {
      return new String(gunzip.readAllBytes());
    } catch (IOException e) {
      Log.error("Error in Unzipping archive: ", e);
      throw new RuntimeException(e);
    }
  }
}
