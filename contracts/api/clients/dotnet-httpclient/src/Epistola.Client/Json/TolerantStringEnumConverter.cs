// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

using System;
using Newtonsoft.Json;
using Newtonsoft.Json.Converters;

namespace Epistola.Client.Json;

/// <summary>
/// Reads an enum value this client does not know as the enum's <c>UnknownDefaultOpenApi</c> member,
/// instead of failing the whole response.
/// </summary>
/// <remarks>
/// A minor version of the contract may add an enum value, so a newer 2.x server can send one this
/// client was not generated with. Newtonsoft's <see cref="StringEnumConverter"/> throws on it, and
/// with it the response it is in. Every generated enum carries <c>UnknownDefaultOpenApi</c>
/// (the generator's <c>enumUnknownDefaultCase</c>), and <c>generate.sh</c> points the generated
/// enums at this converter. Writing is unchanged, as is reading every known value and <c>null</c>.
/// </remarks>
public sealed class TolerantStringEnumConverter : StringEnumConverter
{
    /// <summary>The member every generated enum carries for a value this client does not know.</summary>
    public const string UnknownMemberName = "UnknownDefaultOpenApi";

    /// <inheritdoc />
    public override object? ReadJson(JsonReader reader, Type objectType, object? existingValue, JsonSerializer serializer)
    {
        try
        {
            return base.ReadJson(reader, objectType, existingValue, serializer);
        }
        catch (JsonSerializationException) when (reader.TokenType == JsonToken.String && UnknownMember(objectType) is { } unknown)
        {
            return unknown;
        }
    }

    private static object? UnknownMember(Type objectType)
    {
        var enumType = Nullable.GetUnderlyingType(objectType) ?? objectType;
        return enumType.IsEnum && Enum.IsDefined(enumType, UnknownMemberName)
            ? Enum.Parse(enumType, UnknownMemberName)
            : null;
    }
}
