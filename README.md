# Softa
Metadata-driven, open source enterprise application development framework, including the open source ERP system developed based on this framework.

[Docs](https://www.softa.io/en-US/docs)

[OpenAPI Docs](https://api.softa.io/)

## Design Objectives
1. Focus on Efficiency and Productivity
2. Security and Privacy Protection
3. Flexibility and Scalability

## Key Features
1. Metadata-Driven
2. Built-in Flow
3. OpenAPI
4. Security Controls
5. Data Integration
6. Timeline Model
7. Multilingual Support
8. Multiple Databases Support
9. Multi-Tenancy Support

## Global Placeholder Syntax
Softa uses one placeholder syntax across Flow, document templates, file templates, and code/SQL generation:

- `{{ expr }}` for dynamic values and expressions
- `{{ TriggerParams.status }}` for simple variable paths
- `{{ @fieldName }}` for reserved field references in Filters

Examples:

```text
{{ TriggerParams.id }}
{{ price * qty }}
{{ NOW }}
{{ @createdTime }}
```

The `{{ }}` convention is consistent across:
- **PlaceholderUtils / TemplateEngine**: runtime placeholder resolution
- **Pebble Template Engine**: code generation and DDL generation (see `studio-starter`)
- **AviatorScript**: expression evaluation for computed fields (see `metadata-starter`)

