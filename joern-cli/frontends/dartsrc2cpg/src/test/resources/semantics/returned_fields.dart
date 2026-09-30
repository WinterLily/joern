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

String overwrittenField(String input) {
  final box = make(input);
  box.value = 'constant';
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
