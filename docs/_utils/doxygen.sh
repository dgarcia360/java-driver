#!/bin/bash

cd ..
OUTPUT_DIR="docs/_build/api"

if [[ -z "${GITHUB_REF}" ]]; then
    OUTPUT_DIR="docs/_build/dirhtml/${GITHUB_REF##*/}/api"
    echo "HTML_OUTPUT = $OUTPUT_DIR" >> doxyfile
else
fi

mkdir -p "$OUTPUT_DIR"
echo "$OUTPUT_DIR"
doxygen doxyfile