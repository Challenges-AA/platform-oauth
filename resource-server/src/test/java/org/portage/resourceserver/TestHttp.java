package org.portage.resourceserver;

import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

final class TestHttp {
    private TestHttp() {
    }

    /** Appelle /hello puis transforme le JSON reçu en réponse Spring facile à vérifier. */
    static ResponseEntity<Map> hello(int port, String bearer) throws Exception {
        var result = request(port, bearer);
        Map body = new ObjectMapper().readValue(result.body(), Map.class);
        return ResponseEntity.status(result.statusCode()).body(body);
    }

    /** Envoie la requête GET et ajoute le Bearer uniquement lorsqu'un jeton est fourni. */
    static HttpResponse<String> request(int port, String bearer) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/hello")).GET();
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
