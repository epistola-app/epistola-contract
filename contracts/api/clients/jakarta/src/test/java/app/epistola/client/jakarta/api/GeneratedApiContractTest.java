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
import app.epistola.client.jakarta.error.ProblemDetailException;
import app.epistola.client.jakarta.model.CatalogDependencyListResponse;
import app.epistola.client.jakarta.model.CatalogDraftReleasePreview;
import app.epistola.client.jakarta.model.CatalogVersionDto;
import app.epistola.client.jakarta.model.CatalogVersionListResponse;
import app.epistola.client.jakarta.model.CreateTenantRequest;
import app.epistola.client.jakarta.model.DeploymentDto;
import app.epistola.client.jakarta.model.EnvironmentOverrideDto;
import app.epistola.client.jakarta.model.EnvironmentOverrideListResponse;
import app.epistola.client.jakarta.model.SetEnvironmentOverrideRequest;
import app.epistola.client.jakarta.model.ReleaseCatalogDraftResponse;
import app.epistola.client.jakarta.model.TenantDto;
import app.epistola.client.jakarta.model.UpdateCatalogDependenciesRequest;
import app.epistola.client.jakarta.model.VariantContentDto;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.core.Response;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.junit.jupiter.api.Test;

/**
 * Guards the properties of the generated interfaces that the contract depends on and that a
 * generator upgrade could quietly take away.
 *
 * <p>The vendor media type is the one to watch: the API is versioned by
 * {@code application/vnd.epistola.v2+json}, and media types other than {@code application/json}
 * are a well-known generator blind spot. A client that silently sends {@code application/json}
 * would be talking to the wrong API version.
 */
class GeneratedApiContractTest {

    private static final String VENDOR_JSON = "application/vnd.epistola.v2+json";

    private static final String MULTIPART = "multipart/form-data";

    private static final List<Class<?>> GENERATED_APIS = List.of(
            AttributesApi.class,
            CatalogsApi.class,
            CodeListsApi.class,
            ConsumersApi.class,
            EnvironmentsApi.class,
            FontsApi.class,
            GenerationApi.class,
            ImagesApi.class,
            StencilsApi.class,
            SystemApi.class,
            TemplatesApi.class,
            TenantsApi.class,
            ThemesApi.class,
            VariantsApi.class);

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

    /**
     * A catalog is addressed by its registry, namespace and key together (contract 2.0). Every
     * operation under a catalog takes all three as path parameters, in that order, and none still
     * takes the removed {@code catalogId}.
     */
    @Test
    void catalog_scoped_operations_address_a_catalog_by_registry_namespace_and_key() {
        int catalogScoped = 0;

        for (Class<?> api : GENERATED_APIS) {
            String base = api.getAnnotation(Path.class) == null ? "" : api.getAnnotation(Path.class).value();
            for (Method method : api.getMethods()) {
                List<String> pathParams = pathParams(method);
                String where = api.getSimpleName() + "." + method.getName();
                assertTrue(!pathParams.contains("catalogId"), where + " still takes the removed catalogId");

                Path path = method.getAnnotation(Path.class);
                String fullPath = base + (path == null ? "" : path.value());
                if (!fullPath.contains("{catalogKey}")) {
                    continue;
                }
                catalogScoped++;
                int registry = pathParams.indexOf("registry");
                assertTrue(
                        registry >= 0
                                && registry + 3 <= pathParams.size()
                                && pathParams.subList(registry, registry + 3)
                                        .equals(List.of("registry", "namespace", "catalogKey")),
                        where + " is catalog-scoped but does not take registry, namespace and catalogKey in order: "
                                + pathParams);
            }
        }

        assertTrue(catalogScoped > 0, "expected catalog-scoped operations");
    }

    /**
     * A resource's public address within its parent is its {@code key}; an {@code id} is a
     * generated identifier that is not an address (contract 2.0, #84). No operation still takes a
     * resource address under its old {@code …Id} or {@code slug} name, and no model still carries one.
     */
    @Test
    void resources_are_addressed_and_referenced_by_key() throws Exception {
        List<String> removedNames = List.of(
                "slug", "tenantId", "templateId", "variantId", "environmentId", "stencilId",
                "themeId", "codeListId", "fontId", "imageId", "assetId");

        for (Class<?> api : GENERATED_APIS) {
            for (Method method : api.getMethods()) {
                for (Parameter parameter : method.getParameters()) {
                    PathParam path = parameter.getAnnotation(PathParam.class);
                    QueryParam query = parameter.getAnnotation(QueryParam.class);
                    String name = path != null ? path.value() : query != null ? query.value() : null;
                    assertTrue(
                            name == null || !removedNames.contains(name),
                            api.getSimpleName() + "." + method.getName() + " still takes " + name);
                }
            }
        }

        List<Class<?>> models = generatedModels();
        assertTrue(models.size() > 50, "expected the generated models, found " + models.size());
        for (Class<?> model : models) {
            for (Method method : model.getDeclaredMethods()) {
                String name = method.getName();
                if (!name.startsWith("get") || name.length() < 4) {
                    continue;
                }
                String property = Character.toLowerCase(name.charAt(3)) + name.substring(4);
                assertTrue(
                        !removedNames.contains(property),
                        model.getSimpleName() + " still carries " + property);
            }
        }

        assertEquals("tenantKey", pathParams(method(TemplatesApi.class, "getTemplate")).get(0));
        assertNotNull(TenantDto.class.getMethod("getKey"));
        assertNotNull(CreateTenantRequest.class.getMethod("getKey"));
    }

    /**
     * A catalog's content lives in its versions (contract 3.0): every operation on a catalog's
     * templates, variants, stencils, themes, attributes, code lists, fonts, images, dependencies,
     * changes and readiness takes the version it works on. Only data validation stays at the catalog,
     * because it validates against what is released.
     */
    @Test
    void catalog_content_is_addressed_through_a_catalog_version() {
        List<String> catalogLevel = List.of("/versions", "/versions/{catalogVersion}", "/upgrade", "/upgrade-preview");
        int versionScoped = 0;

        for (Class<?> api : GENERATED_APIS) {
            String base = api.getAnnotation(Path.class) == null ? "" : api.getAnnotation(Path.class).value();
            for (Method method : api.getMethods()) {
                Path path = method.getAnnotation(Path.class);
                String fullPath = base + (path == null ? "" : path.value());
                String catalog = "/catalogs/{registry}/{namespace}/{catalogKey}";
                int at = fullPath.indexOf(catalog);
                if (at < 0) {
                    continue; // not under a catalog, such as a deployment naming a release
                }
                String rest = fullPath.substring(at + catalog.length());
                String where = api.getSimpleName() + "." + method.getName() + " (" + fullPath + ")";
                if (rest.isEmpty() || catalogLevel.contains(rest) || method.getName().equals("validateTemplateData")) {
                    continue;
                }
                versionScoped++;
                assertTrue(rest.startsWith("/versions/{catalogVersion}/"), where + " is catalog content outside a version");
                assertTrue(pathParams(method).contains("catalogVersion"), where + " does not take catalogVersion");
            }
        }

        assertTrue(versionScoped > 20, "expected the catalog's content operations to be version-scoped");
        assertEquals(
                List.of("tenantKey", "registry", "namespace", "catalogKey", "templateKey"),
                pathParams(method(TemplatesApi.class, "validateTemplateData")),
                "validateTemplateData stays at the catalog and takes no version");
    }

    /** The catalog-level release operations were replaced by releasing a draft version. */
    @Test
    void catalog_versions_replace_the_catalog_level_releases() {
        // Drafts are independent copies: releasing one no longer flows into higher drafts, so the
        // incoming-change operations went with forward flow.
        for (String removed : List.of(
                "releaseCatalog",
                "listCatalogReleases",
                "getCatalogRelease",
                "listIncomingChanges",
                "resolveIncomingChange",
                // A draft is numbered when it is released (ADR 0002): the operations
                // that numbered it up front are gone.
                "createCatalogVersion",
                "renumberCatalogVersion",
                "deleteCatalogVersion",
                "previewCatalogVersionRenumber",
                "releaseCatalogVersion",
                // Every draft is released on its own (ADR 0002): there is no next version to
                // promote long-term work to.
                "promoteCatalogDraft")) {
            assertTrue(
                    Arrays.stream(CatalogsApi.class.getMethods()).noneMatch(method -> method.getName().equals(removed)),
                    "CatalogsApi still has the removed " + removed);
        }

        List<String> catalog = List.of("tenantKey", "registry", "namespace", "catalogKey");
        List<String> version = List.of("tenantKey", "registry", "namespace", "catalogKey", "catalogVersion");

        assertOperation("listCatalogVersions", GET.class, CatalogVersionListResponse.class, catalog);
        assertOperation("createCatalogDraft", POST.class, CatalogVersionDto.class, catalog);
        assertOperation("getCatalogVersion", GET.class, CatalogVersionDto.class, version);
        assertOperation("renameCatalogDraft", PATCH.class, CatalogVersionDto.class, version);
        assertOperation("deleteCatalogDraft", DELETE.class, void.class, version);
        assertOperation("previewCatalogDraftRelease", GET.class, CatalogDraftReleasePreview.class, version);
        assertOperation("releaseCatalogDraft", POST.class, ReleaseCatalogDraftResponse.class, version);
        assertTrue(
                method(CatalogsApi.class, "previewCatalogDraftRelease")
                        .getAnnotation(Path.class)
                        .value()
                        .endsWith("/versions/{catalogVersion}/release-preview"),
                "previewCatalogDraftRelease is a release-preview below the version");
        assertTrue(
                Arrays.stream(method(CatalogsApi.class, "previewCatalogDraftRelease").getParameters())
                        .noneMatch(parameter -> parameter.isAnnotationPresent(QueryParam.class)),
                "previewCatalogDraftRelease takes no query parameters");

        for (String withBody :
                List.of("createCatalogDraft", "renameCatalogDraft", "releaseCatalogDraft")) {
            assertEquals(
                    List.of(VENDOR_JSON),
                    List.of(method(CatalogsApi.class, withBody).getAnnotation(Consumes.class).value()),
                    withBody + " should send the vendor media type");
        }
    }

    private static void assertOperation(
            String name, Class<? extends java.lang.annotation.Annotation> verb, Class<?> returns, List<String> path) {
        Method method = method(CatalogsApi.class, name);
        assertNotNull(method.getAnnotation(verb), name + " should be a " + verb.getSimpleName());
        assertEquals(returns, method.getReturnType(), name + " returns the wrong type");
        assertEquals(path, pathParams(method), name + " has the wrong path parameters");
    }

    @Test
    void catalog_dependencies_are_listed_and_replaced() {
        Method list = method(CatalogsApi.class, "listCatalogDependencies");
        assertNotNull(list.getAnnotation(GET.class), "listCatalogDependencies should be a GET");
        assertEquals(CatalogDependencyListResponse.class, list.getReturnType());
        assertEquals(List.of("tenantKey", "registry", "namespace", "catalogKey", "catalogVersion"), pathParams(list));

        Method update = method(CatalogsApi.class, "updateCatalogDependencies");
        assertNotNull(update.getAnnotation(PUT.class), "updateCatalogDependencies should be a PUT");
        assertEquals(CatalogDependencyListResponse.class, update.getReturnType());
        assertEquals(List.of("tenantKey", "registry", "namespace", "catalogKey", "catalogVersion"), pathParams(update));
        assertEquals(UpdateCatalogDependenciesRequest.class, update.getParameterTypes()[5]);
        assertEquals(List.of(VENDOR_JSON), List.of(update.getAnnotation(Consumes.class).value()));
    }

    /**
     * Deploying names the release entirely in the path, so the PUT carries no body; a stale
     * generator output that still expects {@code DeployReleaseRequest} would fail here. Its two
     * extra arguments are the optional {@code includeDependencies} and {@code replace} query
     * parameters, in that order. The deployment carries its usage: {@code lastUsedAt} (null until a
     * request uses it) and {@code documentsRequested}, a count that needs 64 bits.
     */
    @Test
    void a_release_is_deployed_and_undeployed_by_its_path_alone() {
        List<String> releasePath = List.of("tenantKey", "environmentKey", "registry", "namespace", "catalogKey", "releaseVersion");

        Method deploy = method(EnvironmentsApi.class, "deployRelease");
        assertNotNull(deploy.getAnnotation(PUT.class), "deployRelease should be a PUT");
        assertEquals(DeploymentDto.class, deploy.getReturnType());
        assertEquals(releasePath, pathParams(deploy));
        assertEquals(releasePath.size() + 2, deploy.getParameterCount(), "deployRelease should take no request body");
        Parameter includeDependencies = deploy.getParameters()[releasePath.size()];
        assertEquals(Boolean.class, includeDependencies.getType());
        assertEquals("includeDependencies", includeDependencies.getAnnotation(QueryParam.class).value());
        assertEquals("false", includeDependencies.getAnnotation(DefaultValue.class).value());
        Parameter replace = deploy.getParameters()[releasePath.size() + 1];
        assertEquals(Boolean.class, replace.getType());
        assertEquals("replace", replace.getAnnotation(QueryParam.class).value());
        assertEquals("false", replace.getAnnotation(DefaultValue.class).value());
        assertEquals(OffsetDateTime.class, method(DeploymentDto.class, "getLastUsedAt").getReturnType());
        assertEquals(Long.class, method(DeploymentDto.class, "getDocumentsRequested").getReturnType());

        Method undeploy = method(EnvironmentsApi.class, "undeployRelease");
        assertNotNull(undeploy.getAnnotation(DELETE.class), "undeployRelease should be a DELETE");
        assertEquals(void.class, undeploy.getReturnType());
        assertEquals(releasePath, pathParams(undeploy));
        assertEquals(releasePath.size(), undeploy.getParameterCount(), "undeployRelease should take no request body");
    }

    /**
     * An environment override is addressed by the catalog, like a deployment but without a release:
     * one override per catalog. Setting one takes the release or the draft in the body.
     */
    @Test
    void an_environment_override_is_set_listed_and_cleared_by_its_catalog() {
        List<String> catalogPath = List.of("tenantKey", "environmentKey", "registry", "namespace", "catalogKey");

        Method list = method(EnvironmentsApi.class, "listEnvironmentOverrides");
        assertNotNull(list.getAnnotation(GET.class), "listEnvironmentOverrides should be a GET");
        assertEquals(EnvironmentOverrideListResponse.class, list.getReturnType());
        assertEquals(List.of("tenantKey", "environmentKey"), pathParams(list));
        assertTrue(
                list.getAnnotation(Path.class).value().equals("/{environmentKey}/overrides"),
                "listEnvironmentOverrides lists below the environment");

        Method set = method(EnvironmentsApi.class, "setEnvironmentOverride");
        assertNotNull(set.getAnnotation(PUT.class), "setEnvironmentOverride should be a PUT");
        assertEquals(EnvironmentOverrideDto.class, set.getReturnType());
        assertEquals(catalogPath, pathParams(set));
        assertEquals(catalogPath.size() + 1, set.getParameterCount(), "setEnvironmentOverride takes one body");
        assertEquals(SetEnvironmentOverrideRequest.class, set.getParameterTypes()[catalogPath.size()]);
        assertEquals(List.of(VENDOR_JSON), List.of(set.getAnnotation(Consumes.class).value()));
        assertTrue(
                set.getAnnotation(Path.class)
                        .value()
                        .endsWith("/overrides/{registry}/{namespace}/{catalogKey}"),
                "setEnvironmentOverride is addressed by the catalog");

        Method clear = method(EnvironmentsApi.class, "clearEnvironmentOverride");
        assertNotNull(clear.getAnnotation(DELETE.class), "clearEnvironmentOverride should be a DELETE");
        assertEquals(void.class, clear.getReturnType());
        assertEquals(catalogPath, pathParams(clear));
        assertEquals(catalogPath.size(), clear.getParameterCount(), "clearEnvironmentOverride takes no request body");
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
                StubServer.StubResponse.of(201, VENDOR_JSON, "{\"key\":\"acme-corp\",\"name\":\"Acme\"}"))) {

            TenantsApi tenants = EpistolaRestClients.builder()
                    .baseUri(stub.baseUri())
                    .build()
                    .api(TenantsApi.class);

            TenantDto created = tenants.createTenant(new CreateTenantRequest().key("acme-corp").name("Acme"));

            assertEquals("acme-corp", created.getKey());
            StubServer.RecordedRequest request = stub.onlyRequest();
            assertEquals("/api/tenants", request.path());
            assertEquals(VENDOR_JSON, request.header("Content-Type"));
            assertTrue(
                    request.header("Accept").contains(VENDOR_JSON),
                    "Accept should ask for the versioned media type, was: " + request.header("Accept"));
        }
    }

    /**
     * Optimistic locking needs the {@code ETag} a read answers with, and a MicroProfile Rest Client
     * method returns its body alone. The build gives every operation that answers with an
     * {@code ETag} a {@code WithHttpInfo} sibling returning the {@link Response}; this fails if one
     * goes missing or stops matching its operation.
     */
    @Test
    void every_operation_answering_with_an_etag_has_a_response_returning_sibling() {
        List<String> operations = List.of(
                "AttributesApi.getAttribute", "AttributesApi.updateAttribute",
                "CodeListsApi.getCodeList", "CodeListsApi.updateCodeList",
                "StencilsApi.getStencil", "StencilsApi.updateStencil",
                "StencilsApi.getStencilContent", "StencilsApi.updateStencilContent",
                "TemplatesApi.getTemplate", "TemplatesApi.updateTemplate",
                "ThemesApi.getTheme", "ThemesApi.updateTheme",
                "VariantsApi.getVariant", "VariantsApi.updateVariant", "VariantsApi.setDefaultVariant",
                "VariantsApi.getVariantContent", "VariantsApi.updateVariantContent");
        for (String qualified : operations) {
            Class<?> api = GENERATED_APIS.stream()
                    .filter(candidate -> qualified.startsWith(candidate.getSimpleName() + "."))
                    .findFirst()
                    .orElseThrow();
            Method operation = method(api, qualified.substring(qualified.indexOf('.') + 1));
            Method sibling = method(api, operation.getName() + "WithHttpInfo");
            assertEquals(Response.class, sibling.getReturnType(), qualified + "WithHttpInfo should return the Response");
            assertEquals(
                    List.of(operation.getParameterTypes()),
                    List.of(sibling.getParameterTypes()),
                    qualified + "WithHttpInfo should take the operation's parameters");
            assertEquals(pathParams(operation), pathParams(sibling), qualified + "WithHttpInfo has other path parameters");
            for (Class<? extends java.lang.annotation.Annotation> annotation :
                    List.of(GET.class, PUT.class, PATCH.class, POST.class, Path.class, Consumes.class, Produces.class)) {
                assertEquals(
                        String.valueOf(operation.getAnnotation(annotation)),
                        String.valueOf(sibling.getAnnotation(annotation)),
                        qualified + "WithHttpInfo differs from it in @" + annotation.getSimpleName());
            }
        }
    }

    @Test
    void the_etag_of_a_read_is_readable_exactly_as_sent() {
        String content = "{\"variantKey\":\"english\",\"templateModel\":{\"modelVersion\":1,\"root\":\"root\","
                + "\"nodes\":{\"root\":{\"id\":\"root\",\"type\":\"root\",\"slots\":[]}},\"slots\":{},"
                + "\"themeRef\":{\"type\":\"inherit\"}},\"status\":\"modified\","
                + "\"lastModified\":\"2026-10-18T09:15:00Z\",\"readyAt\":null}";
        try (StubServer stub = StubServer.start(request ->
                StubServer.StubResponse.of(200, VENDOR_JSON, content).header("ETag", "\"7\""))) {

            VariantsApi variants = EpistolaRestClients.builder().baseUri(stub.baseUri()).build().api(VariantsApi.class);

            try (Response response = variants.getVariantContentWithHttpInfo(
                    "acme-corp", "~", "acme-corp", "letters", "0193a6b2-7f4e-7c1d-9a2b-3c4d5e6f7a8b", "invoice", "english")) {
                assertEquals("\"7\"", response.getHeaderString("ETag"));
                assertEquals("english", response.readEntity(VariantContentDto.class).getVariantKey());
            }
        }
    }

    @Test
    void an_error_from_a_response_returning_sibling_still_arrives_as_a_problem() {
        String problem = "{\"type\":\"https://epistola.app/errors/not-found\",\"title\":\"Not Found\",\"status\":404}";
        try (StubServer stub = StubServer.start(request ->
                StubServer.StubResponse.of(404, "application/problem+json", problem))) {

            VariantsApi variants = EpistolaRestClients.builder().baseUri(stub.baseUri()).build().api(VariantsApi.class);

            ProblemDetailException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                    ProblemDetailException.class,
                    () -> variants.getVariantContentWithHttpInfo(
                            "acme-corp", "~", "acme-corp", "letters", "0193a6b2-7f4e-7c1d-9a2b-3c4d5e6f7a8b", "invoice", "english"));
            assertEquals("not-found", thrown.getTypeSlug());
        }
    }

    private static List<String> pathParams(Method method) {
        return Arrays.stream(method.getParameters())
                .map(parameter -> parameter.getAnnotation(PathParam.class))
                .filter(annotation -> annotation != null)
                .map(PathParam::value)
                .collect(Collectors.toList());
    }

    /** Every generated model class, read from the directory the model package compiled into. */
    private static List<Class<?>> generatedModels() throws Exception {
        String packageName = TenantDto.class.getPackageName();
        java.nio.file.Path directory = java.nio.file.Path.of(
                TenantDto.class.getResource(TenantDto.class.getSimpleName() + ".class").toURI()).getParent();
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(directory)) {
            List<String> names = files.map(file -> file.getFileName().toString())
                    .filter(file -> file.endsWith(".class") && !file.contains("$"))
                    .map(file -> file.substring(0, file.length() - ".class".length()))
                    .sorted()
                    .collect(Collectors.toList());
            List<Class<?>> models = new java.util.ArrayList<>();
            for (String name : names) {
                models.add(Class.forName(packageName + "." + name));
            }
            return models;
        }
    }

    private static Method method(Class<?> api, String name) {
        return Arrays.stream(api.getMethods())
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(api.getSimpleName() + " has no method " + name));
    }
}
