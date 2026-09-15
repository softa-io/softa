# The `{{ }}` Placeholder & Expression Syntax

Part of the [Softa app authoring guide](../README.md). Softa uses one placeholder
syntax, `{{ ... }}`, across computed fields, filters, and templates. This is what
a downstream app needs to know.

---

## The three kinds of `{{ }}`

| Kind | Looks like | Where |
|---|---|---|
| **Variable** | `{{ TriggerParams.status }}` | dot-path lookup from a context map |
| **Reserved field** | `{{ @createdTime }}` | reference to *another field on the same row* (filters) |
| **Expression** | `{{ price * qty }}` | an AviatorScript formula, evaluated live |

Variables resolve dotted paths against a context map (e.g.
`TriggerParams.owner.name`). Expressions are full AviatorScript — arithmetic,
comparisons, string ops, and a set of imported helpers (date/time, string
utilities).

---

## Computed fields

A computed field derives its value from other fields of the same row. Declare it
with `computed = true` and an `expression`:

```java
@Field(label = "Full Name", computed = true, expression = "firstName + ' ' + lastName")
private String fullName;

@Field(label = "Line Total", computed = true, expression = "unitPrice * quantity")
private BigDecimal lineTotal;
```

Key point: **the `expression` is plain AviatorScript — it is NOT wrapped in
`{{ }}`.** The `{{ }}` wrapper is for template *text* (see below); the
`expression` attribute is already known to be an expression. Variables in scope
are the other fields of the same row (referenced by their field name).

Computed fields are evaluated on read; they are not stored columns.

---

## Filters — comparing one field to another

In a filter, `{{ @fieldName }}` refers to another field on the same row, so you
can express field-to-field conditions (rather than field-to-constant):

```
["endDate", ">=", "{{ @startDate }}"]
```

### The three things a comparison value can be

The same slot holds a constant, a reference to this row, or something read from
the environment. The spelling is what tells them apart, and the `@` is the whole
difference between the first two:

| Written as | Means | Compared against |
|---|---|---|
| `"Others"` | a **constant** | the same value for every row |
| `"{{ @startDate }}"` | **this row's** `startDate` | a different value per row |
| `"{{ TODAY }}"` | an **environment token** | `TODAY` / `YESTERDAY` / `NOW` / `USER_ID` |

So `endDate < "{{ @startDate }}"` reads "before **its own** start date", while
`reason = "Others"` reads "equal to the fixed string Others". Dropping the `@`
changes which of the three you get.

A date base — a field or one of the tokens — takes an ISO-8601 offset:

```
dateOfBirth    > "{{ TODAY - P13Y }}"        // under 13
probationEndOn < "{{ @hireDate + P6M }}"     // within six months of hire
```

### Two failure modes, and only one of them is loud

**A malformed placeholder fails the boot**, in front of whoever wrote it:

```
@Field(invalidWhen) on EmpInfo.endDate: unknown placeholder {{ startDate }};
a value may reference a field ({{ @field }}) or one of [USER_ID, TODAY,
YESTERDAY, NOW], optionally with an ISO-8601 offset.
```

**A wrong option code does not.** `reason = "Other"` (for `"Others"`) is a
perfectly well-formed comparison against a string that no row ever holds, so the
condition simply never fires: no error, no rule, nothing to notice. Compare an
option by its **item code**, and check the code against the enum rather than
retyping it.

### `@` on the left means something else

The field slot accepts two reserved variables, and these read the **context**
rather than the row:

| In the field slot | Reads |
|---|---|
| `@mode` | whether this write is a `create` or an `update` |
| `@userId` | the signed-in user |

```java
@Field(readonlyWhen = """
        @mode = "update"
        """)      // set once, on create; never edited afterwards
```

One symbol, two jobs: on the left `@` names a context variable, inside `{{ }}` on
the right it names a field of this row.

---

## Templates (documents, messages, default values)

Free text with `{{ variable }}` placeholders is rendered against a context map:

```
Order {{ TriggerParams.id }} is {{ TriggerParams.status }}
```
→ with context `{TriggerParams: {id: 1001, status: "PAID"}}` →
`Order 1001 is PAID`.

You'll hit this in message/document templates and in `@Field(defaultValue = "…")`
expressions. The rendering entry points live in `io.softa.framework.base.placeholder`
(`PlaceholderUtils`, `TemplateEngine`) if you need to render a template yourself
in service code.

**HTML templates auto-escape.** Templates rendered as HTML (mail HTML bodies,
rich-text document templates — `TemplateEngine.renderHtml`) HTML-escape every
`{{ }}` output, so data values cannot inject markup. To embed a trusted HTML
fragment, opt out explicitly per value: `{{ fragment | raw }}`. Subjects,
plain-text bodies and SMS are not HTML and are never escaped.

---

## Where you'll use it

| Touchpoint | Form |
|---|---|
| Computed field | `@Field(computed = true, expression = "…")` — bare AviatorScript |
| Default value | `@Field(defaultValue = "…")` — expression evaluated on insert |
| Filter field-to-field | `{{ @otherField }}` inside a filter value |
| Field constraint condition | the same value forms, inside `@Field(requiredWhen / hiddenWhen / readonlyWhen / invalidWhen)` |
| Message / document templates | `{{ variable }}` in the template text |

---

## Common mistakes

1. **Wrapping a computed `expression` in `{{ }}`.** The `expression` attribute is
   raw AviatorScript — write `unitPrice * quantity`, not `{{ unitPrice * quantity }}`.
2. **Using `{{ @field }}` outside a filter.** The reserved-field form is for
   filter conditions (same-row field reference). In computed fields you reference
   other fields by their plain name.
3. **Referencing a field that isn't on the row.** A computed expression can only
   see fields of the same model row (plus the imported helpers).
4. **Dropping the `@` in a filter value.** `{{ startDate }}` is not a field
   reference — it is an unknown token, and it fails the boot with the message
   above. Only `{{ @startDate }}` reads the row.
5. **Comparing an option by its label.** The value is matched against the stored
   **item code**; a label (or a mistyped code) makes the condition silently never
   true.
