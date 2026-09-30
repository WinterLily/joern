(String, String) pair(String input) => (input, 'constant');
(String, String) forward(String input) => pair(input);
String first(String input) => forward(input).$1;
String second(String input) => forward(input).$2;

({String chosen, String other}) named(String input) =>
    (other: 'constant', chosen: input);
String namedField(String input) => named(input).chosen;
String namedOther(String input) => named(input).other;

(String, {String other}) mixed(String input) => (other: 'constant', input);
String mixedField(String input) => mixed(input).$1;
String mixedOther(String input) => mixed(input).other;

String mixedPattern(String input) {
  final (other: _, value) = (input, other: 'constant');
  return value;
}

String destructuredFirst(String input) {
  final (value, _) = forward(input);
  return value;
}

String destructuredSecond(String input) {
  final (_, value) = forward(input);
  return value;
}

String namedPattern(String input) {
  final (chosen: _, other: value) = named(input);
  return value;
}

String nestedFirst(String input) =>
    (payload: pair(input), other: 'constant').payload.$1;
String nestedSecond(String input) =>
    (payload: pair(input), other: 'constant').payload.$2;

String replacedRecord(String input) {
  var record = pair(input);
  record = ('constant', record.$2);
  return record.$1;
}

String independentRecord(String input) {
  final first = pair(input);
  final second = pair('constant');
  first.$1.length;
  return second.$1;
}

String marked(List<String> trace, String label, String value) {
  trace.add(label);
  return value;
}

({String first, String second}) ordered(String input, List<String> trace) => (
  second: marked(trace, 'second', input),
  first: marked(trace, 'first', 'constant'),
);
