#!/bin/bash

OUTPUT_DIR="docs/_build/dirhtml/api"
if [[ -v "${GITHUB_VERSION}" ]]; then
    OUTPUT_DIR="docs/_build/dirhtml/${GITHUB_VERSION}/api"
    echo "HTML_OUTPUT = $OUTPUT_DIR" >> doxyfile
fi
echo "$OUTPUT_DIR"
echo "${GITHUB_VERSION}"
mkdir -p "$OUTPUT_DIR"
doxygen doxyfile
