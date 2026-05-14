# AccessFlow Helm Chart Repository

This branch hosts the [AccessFlow](https://github.com/bablsoft/accessflow) Helm chart repository,
served via GitHub Pages at <https://bablsoft.github.io/accessflow>.

It is managed automatically by `helm/chart-releaser-action` from the `Release` workflow on every
tagged release — do not commit to this branch by hand.

## Install

```bash
helm repo add accessflow https://bablsoft.github.io/accessflow
helm repo update
helm search repo accessflow
```

Chart sources, values reference, and developer docs live on `main` under
[`charts/accessflow/`](https://github.com/bablsoft/accessflow/tree/main/charts/accessflow)
and [`docs/09-deployment.md`](https://github.com/bablsoft/accessflow/blob/main/docs/09-deployment.md).
