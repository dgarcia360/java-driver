#!/bin/bash

cd ..
output_dir = "docs/_build/api"

if [[ -z "${GITHUB_REF}" ]]; then
    output_dir = "docs/_build/dirhtml/${GITHUB_REF##*/}/api"
    echo "HTML_OUTPUT = $output_dir" >> doxyfile
else
fi

mkdir -p "$output_dir"
echo "$output_dir"
doxygen doxyfile