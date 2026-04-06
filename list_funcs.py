import re

with open('app/src/main/java/com/DM/VideoEditor/VideoEditingActivity.kt', 'r', encoding='utf-8') as f:
    text = f.read()

funcs = re.findall(r'^internal fun (.*?)\(.*?$', text, flags=re.MULTILINE)
print("Internal Functions:")
for func in funcs:
    print(func)

