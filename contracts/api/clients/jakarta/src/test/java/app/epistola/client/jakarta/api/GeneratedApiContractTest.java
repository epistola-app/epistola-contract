// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.client.jakarta.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.epistola.client.jakarta.EpistolaRestClients;
import app.epistola.client.jakarta.StubServer;
import app.epistola.client.jakarta.model.CreateTenantRequest;
import app.epistola.client.jakarta.model.TenantDto;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.EntityPart;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.junit.jupiter.api.Test;

/**
 * Guards the properties of the generated interfaces that the contract depends on and that a
 * generator upgrade could quietly take away.
 *
 * <p>The vendor media type is the one to watch: the API is versioned by
 * {@code application/vnd.epistola.v1+json}, and media types other than {@code application/json}
 * are a well-known generator blind spot. A client that silently sends {@code application/json}
 * would be talking to the wrong API version.
 */
class GeneratedApiContractTest {

    private static final String VENDOR_JSON = "application/vnd.epistola.v1+json";

    private static final String MULTIPART = "multipart/form-data";

    private static final List<Class<?>> GENERATED_APIS = List.of(
            AttributesApi.class,
            CatalogsApi.class,
            CodeListsApi.class,
            ConsumersApi.class,
            ContractsApi.class,
            EnvironmentsApi.class,
            FontsApi.class,
            GenerationApi.class,
            ImagesApi.class,
            StencilsApi.class,
            SystemApi.class,
            TemplatesApi.class,
            TenantsApi.class,
            ThemesApi.class,
            VariantsApi.class,
            VersionsApi.class);

    @Test
    void every_generated_api_is_a_microprofile_rest_client() {
        for (Class<?> api : GENERATED_APIS) {
            assertTrue(
                    api.isInterface(), api.getSimpleName() + " should be an interface MicroProfile can proxy");
            assertNotNull(
                    api.getAnnotation(RegisterRestClient.class),
                    api.getSimpleName() + " is missing @RegisterRestClient, so it cannot be injected");
        }
    }

    @Test
    void every_generated_api_registers_the_hand_written_exception_mapper() {
        for (Class<?> api : GENERATED_APIS) {
            RegisterProvider[] providers = api.getAnnotationsByType(RegisterProvider.class);
            boolean registersMapper = Arrays.stream(providers)
                    .anyMatch(provider -> provider.value().equals(ApiExceptionMapper.class));
            assertTrue(
                    registersMapper,
                    api.getSimpleName() + " does not register ApiExceptionMapper, so its errors would not"
                            + " surface as ProblemDetailException");
        }
    }

    @Test
    void json_operations_use_the_versioned_vendor_media_type() {
        Method createTenant = method(TenantsApi.class, "createTenant");
        assertEquals(List.of(VENDOR_JSON), List.of(createTenant.getAnnotation(Consumes.class).value()));
        assertTrue(
                List.of(createTenant.getAnnotation(Produces.class).value()).contains(VENDOR_JSON),
                "createTenant should accept the vendor media type");

        Method generateDocument = method(GenerationApi.class, "generateDocument");
        assertEquals(List.of(VENDOR_JSON), List.of(generateDocument.getAnnotation(Consumes.class).value()));
        assertTrue(
                List.of(generateDocument.getAnnotation(Produces.class).value()).contains(VENDOR_JSON),
                "generateDocument should accept the vendor media type");
    }

    /**
     * Uploads must carry their parts, not their file's path.
     *
     * <p>The generator emits a multipart operation as {@code @FormParam("file") File}, which a
     * MicroProfile Rest Client implementation sends as {@code application/x-www-form-urlencoded}
     * containing the file's local path and none of its bytes. The build rewrites those methods onto
     * {@code List<EntityPart>}, keeping the generated signature as a default method; this fails if a
     * generator upgrade ever restores the broken shape.
     */
    @Test
    void multipart_operations_send_entity_parts() {
        int multipartOperations = 0;

        for (Class<?> api : GENERATED_APIS) {
            for (Method method : api.getMethods()) {
                for (Parameter parameter : method.getParameters()) {
                    assertNull(
                            parameter.getAnnotation(FormParam.class),
                            api.getSimpleName() + "." + method.getName() + " takes a @FormParam, which is sent"
                                    + " as a urlencoded form rather than as multipart");
                }

                Consumes consumes = method.getAnnotation(Consumes.class);
                if (consumes == null || !List.of(consumes.value()).contains(MULTIPART)) {
                    continue;
                }
                multipartOperations++;

                assertTrue(
                        Arrays.stream(method.getParameters())
                                .anyMatch(parameter -> parameter.getType().equals(List.class)
                                        && parameter.getParameterizedType().getTypeName().contains(EntityPart.class.getName())),
                        api.getSimpleName() + "." + method.getName() + " consumes multipart but takes no"
                                + " List<EntityPart> to send");

                assertTrue(
                        Arrays.stream(api.getMethods())
                                .anyMatch(candidate -> candidate.getName().equals(method.getName()) && candidate.isDefault()),
                        api.getSimpleName() + "." + method.getName() + " has no convenience overload, so"
                                + " callers must build parts by hand");
            }
        }

        assertEquals(
                2,
                multipartOperations,
                "expected the contract's two uploads (uploadImage, importCatalog)");
    }

    @Test
    void error_responses_are_declared_as_problem_json() {
        Method getTenant = method(TenantsApi.class, "getTenant");
        assertTrue(
                List.of(getTenant.getAnnotation(Produces.class).value()).contains("application/problem+json"),
                "getTenant should accept problem+json, which is what ApiExceptionMapper parses");
    }

    @Test
    void the_vendor_media_type_is_actually_sent_and_accepted_on_the_wire() {
        try (StubServer stub = StubServer.start(request ->
                StubServer.StubResponse.of(201, VENDOR_JSON, "{\"id\":\"acme-corp\",\"name\":\"Acme\"}"))) {

            TenantsApi tenants = EpistolaRestClients.builder()
                    .baseUri(stub.baseUri())
                    .build()
                    .api(TenantsApi.class);

            TenantDto created = tenants.createTenant(new CreateTenantRequest().id("acme-corp").name("Acme"));

            assertEquals("acme-corp", created.getId());
            StubServer.RecordedRequest request = stub.onlyRequest();
            assertEquals("/api/tenants", request.path());
            assertEquals(VENDOR_JSON, request.header("Content-Type"));
            assertTrue(
                    request.header("Accept").contains(VENDOR_JSON),
                    "Accept should ask for the versioned media type, was: " + request.header("Accept"));
        }
    }

    private static Method method(Class<?> api, String name) {
        return Arrays.stream(api.getMethods())
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(api.getSimpleName() + " has no method " + name));
    }
}
