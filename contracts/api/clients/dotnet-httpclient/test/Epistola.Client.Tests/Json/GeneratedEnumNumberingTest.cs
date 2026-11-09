// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

using System;
using System.Linq;
using Epistola.Client.Json;
using Epistola.Client.Model;
using Xunit;

namespace Epistola.Client.Tests.Json;

public class GeneratedEnumNumberingTest
{
    [Fact]
    public void EveryGeneratedEnumNumbersUnknownDefaultOpenApiZero()
    {
        // C# compiles an enum member into its caller as a number. Were UnknownDefaultOpenApi the
        // last number, as the generator emits it, a value added in a minor version would take that
        // number, and an application built against this client would match the new value in its
        // `case UnknownDefaultOpenApi:` when run with the next one. generate.sh pins it to 0.
        // String enums only. In an integer enum (TemplateDocument.ModelVersionEnum) the number is the
        // wire value, and its unknown member is a fixed sentinel the generator does not renumber.
        var enums = typeof(ConsumerDto).Assembly.GetTypes()
            .Where(t => t.IsEnum && t.Namespace == typeof(ConsumerDto).Namespace)
            .Where(t => Enum.GetNames(t).All(name => !name.StartsWith("NUMBER_", StringComparison.Ordinal)))
            .ToList();
        Assert.NotEmpty(enums);

        var wrong = enums
            .Where(t => !Enum.IsDefined(t, TolerantStringEnumConverter.UnknownMemberName)
                        || Convert.ToInt32(Enum.Parse(t, TolerantStringEnumConverter.UnknownMemberName)) != 0
                        || Enum.GetValues(t).Cast<object>().Count(v => Convert.ToInt32(v) == 0) != 1)
            .Select(t => t.FullName)
            .ToList();
        Assert.Empty(wrong);
    }

    [Fact]
    public void AnUnsetEnumIsUnknownDefaultOpenApi()
    {
        Assert.Equal(ConsumerDto.StatusEnum.UnknownDefaultOpenApi, default(ConsumerDto.StatusEnum));
    }
}
