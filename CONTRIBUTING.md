# Contributing

## Building

```
mvn clean package
```

Builds an HPI at `target/holistic.hpi`. Requires JDK 17+ and Maven 3.6+.

## Running locally

```
mvn hpi:run
```

Boots a Jenkins instance at http://localhost:8080/jenkins with the plugin installed.

## Reporting issues

File issues at https://github.com/jenkinsci/holistic-plugin/issues. Include:

* what you did
* what you expected
* what happened instead
* relevant Jenkins / plugin versions

## Pull requests

Small, focused PRs are easier to review. If you're adding a feature, open an issue first so we can discuss scope before you write code.
