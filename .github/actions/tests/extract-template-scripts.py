#!/usr/bin/env python3
"""Extract the inline bash scripts from the GitLab and Azure CI templates into runnable files.

Usage: extract-template-scripts.py OUT_DIR

Writes one `<platform>-<job>.sh` per script so ci-templates-test.sh can run them against the
fake-curl double and shellcheck can lint them — the YAML files themselves are only parsed and
shellcheck cannot see into a `script:` string. Each GitLab job's single script line is
`bash -euo pipefail -c '<body>'`; the body is unwrapped and prefixed with the same `set` line.
Azure `bash:` steps already start with `set -euo pipefail` and are written verbatim.
Requires PyYAML (present on GitHub-hosted runners).
"""
import pathlib
import re
import sys

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[3]
GITLAB_WRAPPER = re.compile(r"^bash -euo pipefail -c '(?P<body>.*)'\s*$", re.DOTALL)

GITLAB = {
    "ci-templates/gitlab/accessflow.gitlab-ci.yml": {
        ".accessflow_provision_datasource": "gitlab-provision-datasource",
        ".accessflow_run_query": "gitlab-run-query",
    },
    "ci-templates/gitlab/accessflow-deployment.gitlab-ci.yml": {
        ".accessflow_deployment_gate": "gitlab-deployment-gate",
        ".accessflow_deployment_outcome": "gitlab-deployment-outcome",
    },
}
AZURE = "ci-templates/azure/accessflow-deployment.yml"


def write(out_dir: pathlib.Path, name: str, body: str) -> None:
    path = out_dir / f"{name}.sh"
    path.write_text("#!/usr/bin/env bash\n" + body.rstrip("\n") + "\n")
    path.chmod(0o755)
    print(path)


def gitlab_body(job_name: str, job: dict) -> str:
    script = job["script"]
    if len(script) != 1:
        sys.exit(f"{job_name}: expected exactly one script line, got {len(script)}")
    match = GITLAB_WRAPPER.match(script[0])
    if not match:
        sys.exit(f"{job_name}: script line is not wrapped in bash -euo pipefail -c '…'")
    body = match.group("body")
    if "'" in body:
        sys.exit(f"{job_name}: a single quote inside the bash -c '…' body would end the script")
    return "set -euo pipefail\n" + body


def azure_steps(doc: dict):
    # The step list mixes plain bash steps with `${{ each }}` / `${{ if }}` template expressions
    # (dicts keyed by the expression); walk into the latter to find the nested bash steps.
    for step in doc["steps"]:
        if "bash" in step:
            yield step
            continue
        for value in step.values():
            if isinstance(value, list):
                for nested in value:
                    if isinstance(nested, dict) and "bash" in nested:
                        yield nested


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    out_dir = pathlib.Path(sys.argv[1])
    out_dir.mkdir(parents=True, exist_ok=True)

    for rel, jobs in GITLAB.items():
        doc = yaml.safe_load((ROOT / rel).read_text())
        for job_name, out_name in jobs.items():
            write(out_dir, out_name, gitlab_body(job_name, doc[job_name]))

    azure = yaml.safe_load((ROOT / AZURE).read_text())
    steps = list(azure_steps(azure))
    names = {"AccessFlow deployment gate": "azure-deployment-gate",
             "AccessFlow deployment outcome": "azure-deployment-outcome"}
    found = {}
    for step in steps:
        name = names.get(step.get("displayName"))
        if name:
            found[name] = step["bash"]
    missing = set(names.values()) - set(found)
    if missing:
        sys.exit(f"{AZURE}: bash steps not found: {sorted(missing)}")
    for name, body in found.items():
        write(out_dir, name, body)


if __name__ == "__main__":
    main()
