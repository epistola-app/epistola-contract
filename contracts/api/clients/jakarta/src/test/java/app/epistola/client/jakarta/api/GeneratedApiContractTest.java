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
import app.epistola.client.jakarta.model.CatalogDependencyListResponse;
import app.epistola.client.jakarta.model.CatalogVersionDto;
import app.epistola.client.jakarta.model.CatalogVersionListResponse;
import app.epistola.client.jakarta.model.CatalogVersionRenumberPreview;
import app.epistola.client.jakarta.model.CreateTenantRequest;
import app.epistola.client.jakarta.model.DeploymentDto;
import app.epistola.client.jakarta.model.IncomingChangeDto;
import app.epistola.client.jakarta.model.IncomingChangeListResponse;
import app.epistola.client.jakarta.model.ReleaseCatalogVersionResponse;
import app.epistola.client.jakarta.model.TenantDto;
import app.epistola.client.jakarta.model.UpdateCatalogDependenciesRequest;
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
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
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
        for (String removed : List.of("releaseCatalog", "listCatalogReleases", "getCatalogRelease")) {
            assertTrue(
                    Arrays.stream(CatalogsApi.class.getMethods()).noneMatch(method -> method.getName().equals(removed)),
                    "CatalogsApi still has the removed " + removed);
        }

        List<String> catalog = List.of("tenantKey", "registry", "namespace", "catalogKey");
        List<String> version = List.of("tenantKey", "registry", "namespace", "catalogKey", "catalogVersion");

        assertOperation("listCatalogVersions", GET.class, CatalogVersionListResponse.class, catalog);
        assertOperation("createCatalogVersion", POST.class, CatalogVersionDto.class, catalog);
        assertOperation("getCatalogVersion", GET.class, CatalogVersionDto.class, version);
        assertOperation("renumberCatalogVersion", PATCH.class, CatalogVersionDto.class, version);
        assertOperation("deleteCatalogVersion", DELETE.class, void.class, version);
        assertOperation("previewCatalogVersionRenumber", GET.class, CatalogVersionRenumberPreview.class, version);
        assertOperation("releaseCatalogVersion", POST.class, ReleaseCatalogVersionResponse.class, version);
        assertOperation("listIncomingChanges", GET.class, IncomingChangeListResponse.class, version);
        assertOperation(
                "resolveIncomingChange",
                POST.class,
                IncomingChangeDto.class,
                List.of("tenantKey", "registry", "namespace", "catalogKey", "catalogVersion", "changeId"));

        for (String withBody : List.of(
                "createCatalogVersion", "renumberCatalogVersion", "releaseCatalogVersion", "resolveIncomingChange")) {
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
     * generator output that still expects {@code DeployReleaseRequest} would fail here. Its one
     * extra argument is the optional {@code includeDependencies} query parameter.
     */
    @Test
    void a_release_is_deployed_and_undeployed_by_its_path_alone() {
        List<String> releasePath = List.of("tenantKey", "environmentKey", "registry", "namespace", "catalogKey", "releaseVersion");

        Method deploy = method(EnvironmentsApi.class, "deployRelease");
        assertNotNull(deploy.getAnnotation(PUT.class), "deployRelease should be a PUT");
        assertEquals(DeploymentDto.class, deploy.getReturnType());
        assertEquals(releasePath, pathParams(deploy));
        assertEquals(releasePath.size() + 1, deploy.getParameterCount(), "deployRelease should take no request body");
        Parameter includeDependencies = deploy.getParameters()[releasePath.size()];
        assertEquals(Boolean.class, includeDependencies.getType());
        assertEquals("includeDependencies", includeDependencies.getAnnotation(QueryParam.class).value());
        assertEquals("false", includeDependencies.getAnnotation(DefaultValue.class).value());

        Method undeploy = method(EnvironmentsApi.class, "undeployRelease");
        assertNotNull(undeploy.getAnnotation(DELETE.class), "undeployRelease should be a DELETE");
        assertEquals(void.class, undeploy.getReturnType());
        assertEquals(releasePath, pathParams(undeploy));
        assertEquals(releasePath.size(), undeploy.getParameterCount(), "undeployRelease should take no request body");
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
