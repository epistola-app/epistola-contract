// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package app.epistola.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The {@code User-Agent} grammar the contract requires: RFC 9110 product tokens, leading with
 * {@code epistola-contract/{version}}.
 *
 * <p>Both directions live here on purpose. The clients {@link #format(List) build} this header and
 * the server module {@link #parse(String) parses} it, and until they shared one implementation
 * nothing checked that they agreed — a divergence would have made every request from that client
 * unidentifiable, with no error anywhere.
 *
 * <p>The separators are generated per module from the spec's {@code x-client-identity} registry and
 * supplied here, so this class owns the grammar, not the values.
 */
public final class UserAgent {

    /** One {@code name/version} product token. */
    public static final class Product {

        private final String name;
        private final String version;

        public Product(String name, String version) {
            this.name = name;
            this.version = version;
        }

        public String name() {
            return name;
        }

        public String version() {
            return version;
        }

        @Override
        public boolean equals(@Nullable Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Product)) {
                return false;
            }
            Product that = (Product) other;
            return name.equals(that.name) && version.equals(that.version);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, version);
        }

        @Override
        public String toString() {
            return name + "/" + version;
        }
    }

    private final String productSeparator;
    private final String versionSeparator;

    private UserAgent(String productSeparator, String versionSeparator) {
        this.productSeparator = productSeparator;
        this.versionSeparator = versionSeparator;
    }

    /**
     * @param productSeparator between product tokens, e.g. {@code " "}
     * @param versionSeparator between a product name and its version, e.g. {@code "/"}
     */
    public static UserAgent of(String productSeparator, String versionSeparator) {
        if (productSeparator.isEmpty() || versionSeparator.isEmpty()) {
            throw new IllegalArgumentException("User-Agent separators must not be empty");
        }
        return new UserAgent(productSeparator, versionSeparator);
    }

    /** Renders product tokens into a {@code User-Agent} value, in the order given. */
    public String format(List<Product> products) {
        StringBuilder value = new StringBuilder();
        for (Product product : products) {
            if (value.length() > 0) {
                value.append(productSeparator);
            }
            value.append(product.name()).append(versionSeparator).append(product.version());
        }
        return value.toString();
    }

    /**
     * Parses a {@code User-Agent} value into its product tokens. A token with no version separator
     * is returned with an empty version rather than dropped, so an unexpected header is still
     * partially usable.
     *
     * <p>Splits on any run of whitespace rather than the exact separator: this is the parsing half,
     * and RFC 9110 allows more than one space between product tokens.
     */
    public List<Product> parse(@Nullable String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return Collections.emptyList();
        }
        List<Product> products = new ArrayList<>();
        for (String token : userAgent.trim().split("\\s+")) {
            int separator = token.indexOf(versionSeparator);
            if (separator >= 0) {
                products.add(new Product(
                        token.substring(0, separator),
                        token.substring(separator + versionSeparator.length())));
            } else {
                products.add(new Product(token, ""));
            }
        }
        return products;
    }

    /** The version of {@code productName} in {@code products}, or {@code null} when absent. */
    public static @Nullable String versionOf(List<Product> products, String productName) {
        for (Product product : products) {
            if (product.name().equals(productName)) {
                return product.version();
            }
        }
        return null;
    }
}
