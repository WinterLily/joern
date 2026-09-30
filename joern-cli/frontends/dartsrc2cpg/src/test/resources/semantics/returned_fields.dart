class Box {
  String value;
  String other = 'constant';
  Box(this.value);
}

class Pair {
  Box left;
  Box right;
  Pair(this.left, this.right);
}

class Owner {
  String value = 'constant';
  String other = 'constant';

  String write(String input) {
    String current() => value;
    this.other = input;
    current();
    return other;
  }
}

String capturedReceiver(String input) => Owner().write(input);

String compoundField(String input) {
  final box = make(input);
  box.value += '!';
  return box.value;
}

String compoundOtherField(String input) {
  final box = make(input);
  box.other += '!';
  return box.other;
}

Box make(String input) => Box(input);
Box forward(String input) => make(input);
String readValue(String input) => forward(input).value;
String readOther(String input) => forward(input).other;

Pair pair(String input) => Pair(make(input), make('constant'));
String nestedValue(String input) => pair(input).left.value;
String nestedOther(String input) => pair(input).left.other;
String independentMember(String input) => pair(input).right.value;

String aliasValue(String input) {
  final box = make(input);
  final alias = box;
  return alias.value;
}

String aliasOther(String input) {
  final box = make(input);
  final alias = box;
  return alias.other;
}

Box remap(String input) {
  final box = make(input);
  box.other = box.value;
  return box;
}

String copiedField(String input) => remap(input).other;

String aliasWrite(String input) {
  final box = make('constant');
  final alias = box;
  alias.other = input;
  return box.other;
}

String aliasWriteOther(String input) {
  final box = make('constant');
  final alias = box;
  alias.other = input;
  return box.value;
}

String guardedAlias(String input, bool flag) {
  final box = make('constant');
  final Box alias;
  if (flag) {
    alias = box;
  } else {
    return 'constant';
  }
  alias.other = input;
  return box.other;
}

String reboundBase(String input) {
  var box = make('constant');
  final alias = box;
  box = make('constant');
  alias.other = input;
  return box.other;
}

String capturedRebinding(String input) {
  var box = make('constant');
  String replace() {
    box = make('replacement');
    return input;
  }

  box.other = replace();
  return box.other;
}

String overwrittenField(String input) {
  final box = make(input);
  box.value = 'constant';
  return box.value;
}

void replaceValue(Box box) {
  box.value = 'constant';
}

void replaceOther(Box box) {
  box.other = 'constant';
}

void writeValue(Box box, String input) {
  box.value = input;
}

String calleeOverwrite(String input) {
  final box = make(input);
  replaceValue(box);
  return box.value;
}

String calleeOtherOverwrite(String input) {
  final box = make(input);
  replaceOther(box);
  return box.value;
}

String calleeWrite(String input) {
  final box = make('constant');
  writeValue(box, input);
  return box.value;
}

String calleeWriteOther(String input) {
  final box = make('constant');
  writeValue(box, input);
  return box.other;
}

String calleeIndependent(String input) {
  final first = make('constant');
  final second = make('constant');
  writeValue(first, input);
  writeValue(second, 'constant');
  return second.value;
}

String calleeAliasOverwrite(String input) {
  final box = make(input);
  final alias = box;
  replaceValue(alias);
  return box.value;
}

String calleeAliasPreserved(String input) {
  final box = make('constant');
  box.other = input;
  final alias = box;
  replaceValue(alias);
  return box.other;
}

void replaceConditional(Box box, bool flag) {
  if (flag) box.value = 'constant';
}

String calleeConditional(String input, bool flag) {
  final box = make(input);
  replaceConditional(box, flag);
  return box.value;
}

void replaceThrowing(Box box, bool flag) {
  box.value = replacement(flag);
}

String calleeThrowing(String input, bool flag) {
  final box = make(input);
  try {
    replaceThrowing(box, flag);
  } catch (_) {}
  return box.value;
}

Never replaceThenThrow(Box box) {
  box.value = 'constant';
  throw 'failed';
}

String calleeThrowAfterWrite(String input) {
  final box = make(input);
  try {
    replaceThenThrow(box);
  } catch (_) {}
  return box.value;
}

void rebind(Box box) {
  box = make('constant');
}

String calleeRebind(String input) {
  final box = make(input);
  rebind(box);
  return box.value;
}

String calleeSavedValue(String input) {
  final box = make(input);
  final before = box.value;
  replaceValue(box);
  return before;
}

String conditionalOverwrite(String input, bool flag) {
  final box = make(input);
  if (flag) box.value = 'constant';
  return box.value;
}

String bothBranchesOverwrite(String input, bool flag) {
  final box = make(input);
  if (flag) {
    box.value = 'constant';
  } else {
    box.value = 'constant';
  }
  return box.value;
}

String replacement(bool fail) {
  if (fail) throw 'failed';
  return 'constant';
}

String throwingOverwrite(String input, bool flag) {
  final box = make(input);
  try {
    box.value = replacement(flag);
  } catch (_) {}
  return box.value;
}

String reintroducedField(String input) {
  final box = make(input);
  box.value = 'constant';
  box.value = input;
  return box.value;
}

String readBeforeOverwrite(String input) {
  final box = make(input);
  final before = box.value;
  box.value = 'constant';
  return before;
}

String parentFieldOverwrite(String input) {
  final boxes = pair(input);
  boxes.left = make('constant');
  return boxes.left.value;
}

String otherParentOverwrite(String input) {
  final boxes = pair(input);
  boxes.right = make('constant');
  return boxes.left.value;
}

String optionalLoopOverwrite(String input, int count) {
  final box = make(input);
  for (var index = 0; index < count; index++) {
    box.value = 'constant';
  }
  return box.value;
}

String mandatoryLoopOverwrite(String input, int count) {
  final box = make(input);
  var index = 0;
  do {
    box.value = 'constant';
    index++;
  } while (index < count);
  return box.value;
}

String independentObjects(String input) {
  final first = make(input);
  final second = make('constant');
  first.value.toString();
  return second.value;
}

Never throwBox(String input) => throw make(input);

String caughtValue(String input) {
  try {
    throwBox(input);
  } catch (error) {
    return (error as Box).value;
  }
}

String caughtOther(String input) {
  try {
    throwBox(input);
  } catch (error) {
    return (error as Box).other;
  }
}
