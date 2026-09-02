// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

/**
 * Wire-protocol logic shared by the Epistola JVM clients and the server stubs.
 *
 * <p>The whole package is {@link org.jspecify.annotations.NullMarked}: every type is non-null
 * unless it carries {@link org.jspecify.annotations.Nullable}. That is for the Kotlin consumers'
 * benefit — without it Kotlin sees platform types ({@code String!}) and silently drops null-safety
 * at exactly the places where {@code null} carries meaning here: a partition assignment the server
 * has not sent yet, or a problem type that has no Epistola slug.
 */
@NullMarked
package app.epistola.protocol;

import org.jspecify.annotations.NullMarked;
