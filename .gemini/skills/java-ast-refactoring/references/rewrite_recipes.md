# OpenRewrite Recipe Reference

OpenRewrite uses declarative YAML recipes to perform structural refactorings.

## Recipe Structure

A recipe file must have a `type`, a `name` (which you will activate), and a `recipeList` containing specific core recipes to execute sequentially.

```yaml
type: specs.openrewrite.org/v1beta/recipe
name: com.example.MyRefactoring
recipeList:
  - <CoreRecipe>:
      <argument1>: <value>
```

## Core Recipes for Common Operations

### 1. Change Method Name
```yaml
  - org.openrewrite.java.ChangeMethodName:
      methodPattern: java.util.Collections emptyList()
      newMethodName: emptyList
```

### 2. Change Method Target to Static
Moves a method call to a new static method target. Useful for replacing custom utility methods with standard ones.
```yaml
  - org.openrewrite.java.ChangeMethodTargetToStatic:
      methodPattern: google.registry.model.eppinput.EppInputs createDomain(java.lang.String, java.lang.String)
      fullyQualifiedTargetTypeName: google.registry.model.domain.DomainCommand.Create
      returnType: google.registry.model.domain.DomainCommand.Create.Builder
```

### 3. Change Type (Rename/Move Class)
Updates the class name and automatically updates all imports across the codebase.
*Note: OpenRewrite occasionally drops `import static` references to fields inside the renamed class. Be prepared to manually restore them if a compilation error occurs.*
```yaml
  - org.openrewrite.java.ChangeType:
      oldFullyQualifiedTypeName: org.joda.time.Instant
      newFullyQualifiedTypeName: java.time.Instant
```

### 4. Remove Unused Imports
```yaml
  - org.openrewrite.java.RemoveUnusedImports
```

### 5. Change Annotation
```yaml
  - org.openrewrite.java.ChangeAnnotation:
      annotationPattern: @org.junit.Ignore
      newAnnotation: @org.junit.jupiter.api.Disabled
```

### 6. Remove Annotation
```yaml
  - org.openrewrite.java.RemoveAnnotation:
      annotationPattern: @java.lang.SuppressWarnings("unchecked")
```

### 7. Change Method Arguments
Reorders or removes arguments based on a target signature. `newArgumentTemplate` uses 0-based indexing.
```yaml
  - org.openrewrite.java.ChangeMethodAccessLevel:
      methodPattern: com.google.common.collect.ImmutableList of(..)
      newAccessLevel: protected
```

### 8. Add Import
```yaml
  - org.openrewrite.java.AddImport:
      type: java.util.List
```

## Method Patterns
OpenRewrite uses a specific pointcut expression language for `methodPattern`:
* `[return-type] [fully-qualified-class-name] [method-name]([parameter-types])`
* `*` matches any type.
* `..` matches any number of parameters.
* Example: `java.lang.String split(java.lang.String, int)`
* Example: `* java.util.List add(..)`
