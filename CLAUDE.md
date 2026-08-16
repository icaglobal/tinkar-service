<!-- BEGIN ike-managed: standards-pointer -->

## IKE Build Standards

This project follows the IKE build standards. Run `mvn validate` to
unpack them into `.claude/standards/` — build artifacts from
`ike-build-standards`, so **do not edit or commit them** — then read and
follow them (start with `MAVEN.md` and `IKE-MAVEN.md`).

Diagrams on web pages (`src/site/asciidoc/`) follow `IKE-DIAGRAMS.md`:
pre-render to committed static SVG under `src/site/resources/images/` and
reference with `image::` — never inline `[plantuml]`/`[graphviz]` blocks
or live Kroki URLs (the Maven site parser does not render them).
<!-- END ike-managed: standards-pointer -->
