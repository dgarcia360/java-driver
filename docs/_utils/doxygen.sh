#!/bin/bash

# Clone repo
cd ..
output_dir = "docs/_build/dirhtml/${GITHUB_REF##*/}/api"
echo output_dir
mkdir -p $output_dir
echo "HTML_OUTPUT = $output_dir" >> doxyfile
doxygen doxyfile