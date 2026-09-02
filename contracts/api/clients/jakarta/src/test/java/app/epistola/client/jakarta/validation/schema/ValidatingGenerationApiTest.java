// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.validation.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.epistola.client.jakarta.FakeApis;
import app.epistola.client.jakarta.api.GenerationApi;
import app.epistola.client.jakarta.api.TemplatesApi;
import app.epistola.client.jakarta.model.BatchGenerationItem;
import app.epistola.client.jakarta.model.GenerateBatchRequest;
import app.epistola.client.jakarta.model.GenerateDocumentRequest;
import app.epistola.client.jakarta.model.GenerationJobResponse;
import app.epistola.client.jakarta.model.TemplateDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ValidatingGenerationApiTest {

    private static final Map<String, Object> INVOICE_SCHEMA = Map.of(
            "type", "object",
            "required", List.of("customerName"),
            "properties", Map.of("customerName", Map.of("type", "string")));

    private final List<String> submitted = new ArrayList<>();

    private final TemplatesApi templatesApi = FakeApis.of(
            TemplatesApi.class,
            Map.of("getTemplate", args -> new TemplateDto()
                    .id((String) args[2])
                    .tenantId((String) args[0])
                    .name((String) args[2])
                    .schema(INVOICE_SCHEMA)));

    private final GenerationApi generationApi = FakeApis.of(
            GenerationApi.class,
            Map.of(
                    "generateDocument", args -> {
                        submitted.add("single");
                        return new GenerationJobResponse().requestId(UUID.randomUUID());
                    },
                    "generateDocumentBatch", args -> {
                        submitted.add("batch");
                        return new GenerationJobResponse().requestId(UUID.randomUUID());
                    }));

    @Test
    void valid_data_is_forwarded_to_the_delegate() {
        ValidatingGenerationApi api = new ValidatingGenerationApi(generationApi, templatesApi);

        api.generateDocument("acme-corp", request(Map.of("customerName", "Jane")));

        assertEquals(List.of("single"), submitted);
    }

    @Test
    void invalid_data_never_reaches_the_server() {
        ValidatingGenerationApi api = new ValidatingGenerationApi(generationApi, templatesApi);

        assertThrows(
                TemplateDataValidationException.class,
                () -> api.generateDocument("acme-corp", request(Map.of("wrongField", "Jane"))));

        assertTrue(submitted.isEmpty(), "a request that cannot succeed should not cost a round trip");
    }

    @Test
    void a_valid_batch_is_forwarded_once() {
        ValidatingGenerationApi api = new ValidatingGenerationApi(generationApi, templatesApi);

        api.generateDocumentBatch(
                "acme-corp",
                new GenerateBatchRequest()
                        .items(List.of(item(Map.of("customerName", "A")), item(Map.of("customerName", "B")))));

        assertEquals(List.of("batch"), submitted);
    }

    @Test
    void every_failing_batch_item_is_reported_at_once_with_its_index() {
        ValidatingGenerationApi api = new ValidatingGenerationApi(generationApi, templatesApi);

        TemplateDataValidationException e = assertThrows(
                TemplateDataValidationException.class,
                () -> api.generateDocumentBatch(
                        "acme-corp",
                        new GenerateBatchRequest()
                                .items(List.of(
                                        item(Map.of("customerName", "A")),
                                        item(Map.of("nope", "B")),
                                        item(Map.of("customerName", "C")),
                                        item(Map.of("also-nope", "D"))))));

        // Fixing a hundred-item batch one failure per round trip is the thing to avoid.
        assertEquals(2, e.getErrors().size(), e.formatErrors());
        assertTrue(e.getErrors().get(0).getPath().startsWith("items[1]"), e.getErrors().get(0).getPath());
        assertTrue(e.getErrors().get(1).getPath().startsWith("items[3]"), e.getErrors().get(1).getPath());
        assertTrue(submitted.isEmpty());
    }

    private static GenerateDocumentRequest request(Object data) {
        return new GenerateDocumentRequest()
                .catalogId("default")
                .templateId("invoice")
                .variantId("english")
                .data(data);
    }

    private static BatchGenerationItem item(Object data) {
        return new BatchGenerationItem()
                .catalogId("default")
                .templateId("invoice")
                .variantId("english")
                .data(data);
    }
}
