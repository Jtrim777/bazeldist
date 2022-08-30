# bazeldist
> Adapted from [vaticle/bazel-distribution](https://github.com/vaticle/bazel-distribution)

Bazel rules for packaging and deploying projects of a myriad of formats.

## Modifications from Original
- Causes `deploy_artifact` to use the same VERSION file as other rules
- Upgrades deploy scripts to use python3 (thus mitigating the macOS python
  env error)
- Improves error handling & progress displays in deploy scripts
- Standardizes token names for package repo auth
- Allows deploying uber-Jars to Maven repositories
- Adds a self-packaging rule such that `bazeldist` itself is available as an HTTP archive
