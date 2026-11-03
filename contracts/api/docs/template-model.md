# The template model

How a template's content is written, for anyone who builds or changes templates through the API rather
than in the editor, including an AI. A variant's content is a **template document**: the
`templateModel` of `getVariantContent` and `updateVariantContent`, and of a stencil's content.

This guide explains the model and walks through one small letter. It does not list every component
or style: the catalog contract's registries are the source of truth, and they carry an example per
component.

- [`component-registry.json`](https://github.com/epistola-app/epistola-contract/blob/main/contracts/catalog/registry/component-registry.json): every component type, its slots,
  which children it allows, its properties and the styles that apply to it, with examples.
- [`style-registry.json`](https://github.com/epistola-app/epistola-contract/blob/main/contracts/catalog/registry/style-registry.json): every style property, its type and units.
- [`template-document.schema.json`](../../catalog/schemas/template-document.schema.json): the shape
  of the document itself.

The full example used below is [`examples/template-letter.json`](examples/template-letter.json),
and it is the example the API reference shows for `updateVariantContent`. A test validates it, and
checks that every excerpt on this page is quoted from it, so neither can go stale.

## The document: a flat graph of nodes and slots

A document is not a nested tree. It is two flat maps, `nodes` and `slots`, linked by id:

- A **node** is one block: a paragraph, a table, a conditional. Its `type` is a component type from
  the registry, its `props` are that component's properties, and its `slots` lists the ids of the
  slots it owns, in order.
- A **slot** is a named place inside a node that holds child nodes, in order: a table's
  `cell-0-1`, a conditional's `body` and `else`. It names its owner (`nodeId`) and its `children`.
- `root` names the one node of type `root`; its `children` slot is the document body, top to bottom.

Every id is unique within the document and chosen by whoever writes it; keep them stable, because a
change is easier to follow when ids do not move. `modelVersion` is `1`, and `themeRef` says which
theme renders the document: `{"type": "inherit"}` takes the template's or the catalog's default,
`{"type": "override", "themeKey": "…"}` names one.

```text
root ── children: heading, greeting, paid-check, order-table, signature
```

## Text and expressions

A `text` node's `props.content` is a rich-text document (`type: doc`) of paragraphs and headings.
Inside it, an `expression` node inserts a value from the data the document is generated with. An
expression is [JSONata](https://jsonata.org), evaluated against that data: `customer.name`,
`$formatNumber(order.total, '#,##0.00')`.

```json
{
  "id": "heading",
  "type": "text",
  "slots": [],
  "props": {
    "content": {
      "type": "doc",
      "content": [
        {
          "type": "heading",
          "attrs": {
            "level": 1
          },
          "content": [
            {
              "type": "text",
              "text": "Order confirmation"
            }
          ]
        }
      ]
    }
  }
}
```

```json
{
  "id": "greeting",
  "type": "text",
  "slots": [],
  "props": {
    "content": {
      "type": "doc",
      "content": [
        {
          "type": "paragraph",
          "content": [
            {
              "type": "text",
              "text": "Dear "
            },
            {
              "type": "expression",
              "attrs": {
                "expression": "customer.name"
              }
            },
            {
              "type": "text",
              "text": ", thank you for your order "
            },
            {
              "type": "expression",
              "attrs": {
                "expression": "order.number"
              }
            },
            {
              "type": "text",
              "text": "."
            }
          ]
        }
      ]
    }
  }
}
```

## A conditional with an else branch

A `conditional` renders its `body` slot when `props.condition` is truthy, and its optional `else`
slot otherwise. `inverse: true` inverts the condition.

```json
{
  "paid-check": {
    "id": "paid-check",
    "type": "conditional",
    "slots": [
      "paid-check-body",
      "paid-check-else"
    ],
    "props": {
      "condition": {
        "raw": "order.paid",
        "language": "jsonata"
      },
      "inverse": false
    }
  },
  "paid-check-body": {
    "id": "paid-check-body",
    "nodeId": "paid-check",
    "name": "body",
    "children": [
      "paid-text"
    ]
  },
  "paid-check-else": {
    "id": "paid-check-else",
    "nodeId": "paid-check",
    "name": "else",
    "children": [
      "unpaid-text"
    ]
  }
}
```

## A table

A `table` declares its size in `props.rows` and `props.columns`, and owns one slot per cell, named
`cell-<row>-<col>` with 0-based indexes. `headerRows` marks the first rows as header rows, and
`cellStyles` styles single cells under the same `<row>-<col>` keys. A node's own `styles` override
what its theme and preset supply, including the theme's default block spacing; `6pt` is an absolute length, `1.5sp` would be
one and a half spacing units of the theme.

```json
{
  "id": "order-table",
  "type": "table",
  "slots": [
    "order-table-cell-0-0",
    "order-table-cell-0-1",
    "order-table-cell-1-0",
    "order-table-cell-1-1"
  ],
  "styles": {
    "marginBottom": "6pt"
  },
  "props": {
    "rows": 2,
    "columns": 2,
    "columnWidths": [
      70,
      30
    ],
    "headerRows": 1,
    "merges": [],
    "cellStyles": {
      "0-0": {
        "backgroundColor": "#f1f5f9"
      },
      "0-1": {
        "backgroundColor": "#f1f5f9"
      }
    }
  }
}
```

```json
{
  "order-table-cell-0-0": {
    "id": "order-table-cell-0-0",
    "nodeId": "order-table",
    "name": "cell-0-0",
    "children": [
      "cell-item-h"
    ]
  },
  "order-table-cell-1-1": {
    "id": "order-table-cell-1-1",
    "nodeId": "order-table",
    "name": "cell-1-1",
    "children": [
      "cell-amount"
    ]
  }
}
```

## A stencil instance

A stencil is reusable content, such as a signature block, kept as a resource of its own. A template
does not copy it: a `stencil` node names it by `props.stencilKey` (and `props.catalogKey`, the alias
of the catalog that holds it, when that is not the template's own), and binds the stencil's
parameters to expressions over the template's data in `props.parameterBindings`. The stencil is read
when the document is rendered or edited, so a change to it reaches every template that uses it.

What the template supplies goes in the instance's `children` slot: one `placeholder` node per
placeholder of the stencil it fills, named as the stencil names it, with the content in its `fill`
slot.

```json
{
  "signature": {
    "id": "signature",
    "type": "stencil",
    "slots": [
      "signature-children"
    ],
    "props": {
      "stencilKey": "signature-block",
      "parameterBindings": {
        "signerName": "sender.name"
      }
    }
  },
  "signature-children": {
    "id": "signature-children",
    "nodeId": "signature",
    "name": "children",
    "children": [
      "signature-closing"
    ]
  },
  "signature-closing": {
    "id": "signature-closing",
    "type": "placeholder",
    "slots": [
      "signature-closing-fill"
    ],
    "props": {
      "name": "closing"
    }
  },
  "signature-closing-fill": {
    "id": "signature-closing-fill",
    "nodeId": "signature-closing",
    "name": "fill",
    "children": [
      "closing-text"
    ]
  }
}
```

## Styles and presets

Each node may carry `styles`, CSS-like key and value pairs from the style registry, and a
`stylePreset`, a named block style the catalog declares and its themes style. Which styles apply to
which component is in the component registry (`applicableStyles`); the validator warns about one
that does not apply, or that the style registry does not define. From lowest to highest: the
component's structural defaults (such as `listItemSpacing`), the theme's `defaultBlockStyles` (the
vertical rhythm between blocks, `marginTop` and `marginBottom` only), the document styles, the
preset, and the node's `styles`. When the theme sets no `defaultBlockStyles`, the renderer supplies
its own fallback spacing.

## Checking what you write

`updateVariantContent` validates the document and refuses an invalid one with a
`validation-error` problem (400) whose `errors` list each finding with its `field` and `message`. To check a document before sending it, the catalog contract's
`TemplateValidator` (Kotlin, `app.epistola.contract:epistola-catalog`) gives the same findings.
