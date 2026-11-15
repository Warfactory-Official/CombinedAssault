"""Delete / set members and delete list entries in JSON text, leaving every other byte as it was.

Path steps: str = object key; {"id": x} = the list entry whose "id" is x.
"""
import json
import re

_WS = re.compile(r"\s*")


class _Node:
    """Value span [start, end); object: members [(key, key_start, value)]; array: items [value]."""

    def __init__(self, start, end, kind, children=None, value=None):
        self.start, self.end, self.kind, self.children, self.value = start, end, kind, children, value


def _parse(text, i):
    i = _WS.match(text, i).end()
    c = text[i]
    if c == "{":
        members, j = [], _WS.match(text, i + 1).end()
        if text[j] == "}":
            return _Node(i, j + 1, "object", members)
        while True:
            key_start = j
            key, j = json.decoder.scanstring(text, j + 1)
            j = _WS.match(text, j).end()
            assert text[j] == ":", (j, text[j - 20:j + 20])
            value = _parse(text, j + 1)
            members.append((key, key_start, value))
            j = _WS.match(text, value.end).end()
            if text[j] == "}":
                return _Node(i, j + 1, "object", members)
            assert text[j] == ",", (j, text[j - 20:j + 20])
            j = _WS.match(text, j + 1).end()
    if c == "[":
        items, j = [], _WS.match(text, i + 1).end()
        if text[j] == "]":
            return _Node(i, j + 1, "array", items)
        while True:
            value = _parse(text, j)
            items.append(value)
            j = _WS.match(text, value.end).end()
            if text[j] == "]":
                return _Node(i, j + 1, "array", items)
            assert text[j] == ",", (j, text[j - 20:j + 20])
            j = _WS.match(text, j + 1).end()
    value, end = json.JSONDecoder().raw_decode(text, i)
    return _Node(i, end, "scalar", value=value)


def _entry_id(text, node):
    if node.kind != "object":
        return None
    for key, _, value in node.children:
        if key == "id" and value.kind == "scalar":
            return value.value
    return None


def _cut(text, starts, ends, index):
    """Span removing element `index` of a container whose element spans are starts/ends, with one separator."""
    if len(starts) == 1:
        return starts[0], ends[0]
    if index < len(starts) - 1:
        return starts[index], starts[index + 1]
    return ends[index - 1], ends[index]


def _walk(text, path):
    """(container node, index of the last step or None when absent). Missing intermediate step => KeyError."""
    if not path:
        raise ValueError("empty path")
    node = _parse(text, 0)
    for depth, step in enumerate(path):
        if isinstance(step, dict):
            assert node.kind == "array", step
            index = next((k for k, item in enumerate(node.children) if _entry_id(text, item) == step["id"]), None)
        else:
            assert node.kind == "object", step
            index = next((k for k, (key, _, _) in enumerate(node.children) if key == step), None)
        if depth == len(path) - 1:
            return node, index
        if index is None:
            raise KeyError(step)
        node = node.children[index] if isinstance(step, dict) else node.children[index][2]


def remove(text, path):
    """Text with `path` removed; KeyError if it is not there."""
    node, index = _walk(text, path)
    if index is None:
        raise KeyError(path[-1])
    if node.kind == "array":
        a, b = _cut(text, [n.start for n in node.children], [n.end for n in node.children], index)
    else:
        a, b = _cut(text, [m[1] for m in node.children], [m[2].end for m in node.children], index)
    return text[:a] + text[b:]


def put(text, path, value):
    """Text with object key `path` set to `value` (json.dumps one-liner); absent key appended after the last member,
    separated like the members before it."""
    node, index = _walk(text, path)
    assert node.kind == "object", path
    dumped = json.dumps(value, ensure_ascii=False)
    if index is not None:
        span = node.children[index][2]
        return text[:span.start] + dumped + text[span.end:]
    member = json.dumps(path[-1], ensure_ascii=False) + ": " + dumped
    if not node.children:
        return text[:node.start + 1] + member + text[node.end - 1:]
    members = node.children
    gap = text[members[-2][2].end:members[-1][1]] if len(members) > 1 else "," + text[node.start + 1:members[0][1]]
    at = members[-1][2].end
    return text[:at] + gap + member + text[at:]


if __name__ == "__main__":
    sample = '{\n    "a": [1, 2],\n    "parts": [\n        {"id": "x", "k": 1, "seat": [0, 1]},\n        {"id": "y"}\n    ],\n    "z": {"q": 1}\n}\n'
    out = remove(sample, ["parts", {"id": "x"}, "seat"])
    out = remove(out, ["parts", {"id": "y"}])
    out = remove(out, ["z", "q"])
    out = remove(out, ["a"])
    assert json.loads(out) == {"parts": [{"id": "x", "k": 1}], "z": {}}, out
    out = put(out, ["z", "q"], [1, 2])
    out = put(out, ["parts", {"id": "x"}, "k"], 2)
    out = put(out, ["n"], None)
    assert json.loads(out) == {"parts": [{"id": "x", "k": 2}], "z": {"q": [1, 2]}, "n": None}, out
    print(out)
