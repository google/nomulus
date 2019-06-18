import fnmatch
import os
import sys
import re

template = re.compile("""^\/\/ Copyright \d{4} The Nomulus Authors\. All Rights Reserved\.
\/\/
\/\/ Licensed under the Apache License, Version 2\.0 \(the "License"\);
\/\/ you may not use this file except in compliance with the License\.
\/\/ You may obtain a copy of the License at
\/\/
\/\/     http:\/\/www\.apache\.org\/licenses\/LICENSE-2\.0
\/\/
\/\/ Unless required by applicable law or agreed to in writing, software
\/\/ distributed under the License is distributed on an "AS IS" BASIS,
\/\/ WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied\.
\/\/ See the License for the specific language governing permissions and
\/\/ limitations under the License\.""")

def get_files():
	result = []
	for root, dirnames, filenames in os.walk("."):
		paths = [os.path.join(root, filename) for filename in filenames]
		for path in fnmatch.filter(paths, "**/src/**/java/**/*.java"):
			result.append(path)
	return result

if __name__ == "__main__":
	all_java_files = get_files()
	failed_files = []

	for java_file in all_java_files:
		with open(java_file, 'r') as f:
			file_content = f.read()
			if not re.match(template, file_content):
				failed_files.append(java_file)

	if failed_files:
		print("The following files did not match the license header: " + str(failed_files))
		sys.exit(1)
