# Softa Web

## Option Sets
Option sets provide a shared, ordered list of selectable values. The web module exposes an API to read option items
and the ORM layer caches them for fast lookup.

### Data Source
Option items are stored in the metadata table `SysOptionItem`. Each item belongs to an `optionSetCode` and has an
`itemCode`, `itemName`, and a `sequence` used for ordering.

### Cache Behavior
At application startup, `OptionManager.init()` loads all `SysOptionItem` rows, ordered by `sequence`, and stores them
in an in-memory cache keyed by `optionSetCode`. Retrieval preserves the original order.

### API Usage
Read option items by option set code:

```
GET /SysOptionSet/getOptionItems/{optionSetCode}
```

Example:
```
GET /SysOptionSet/getOptionItems/OrderStatus
```

Response is a list of `MetaOptionItem` objects.

### Programmatic Usage
Use `OptionManager` in service code:

```java
List<MetaOptionItem> items = OptionManager.getMetaOptionItems("OrderStatus");
MetaOptionItem pending = OptionManager.getMetaOptionItem("OrderStatus", "PENDING");
String pendingName = OptionManager.getItemNameByCode("OrderStatus", "PENDING");
boolean exists = OptionManager.existsItemCode("OrderStatus", "PENDING");
```

### Localization
`MetaOptionItem.getItemName()` returns a translated name if a translation exists for the current language in context.
If no translation exists, it returns the original `itemName`.

### OptionReference Structure
When option values are expanded or returned as references, they use `OptionReference`.

Fields:
- `itemCode`: option item code.
- `itemName`: option item display name.
- `itemColor`: optional color string.

### Notes
- `OptionManager` throws if `optionSetCode` does not exist in cache. Validate with
  `OptionManager.existsOptionSetCode` when needed.
- Option set data must be present in the database before startup, or the cache will be empty.
