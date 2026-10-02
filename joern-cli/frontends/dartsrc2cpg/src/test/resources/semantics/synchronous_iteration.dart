final trace = <String>[];

class Values<T> extends Iterable<T> {
  final List<T> items;
  Values(this.items);

  @override
  Cursor<T> get iterator {
    trace.add('iterator');
    return Cursor(items);
  }
}

class Cursor<T> implements Iterator<T> {
  final List<T> items;
  var index = -1;
  Cursor(this.items);

  @override
  bool moveNext() {
    trace.add('moveNext');
    return ++index < items.length;
  }

  @override
  T get current {
    trace.add('current');
    return items[index];
  }
}

class UnrelatedCursor implements Iterator<String> {
  @override
  bool moveNext() => false;

  @override
  String get current => 'unrelated';
}

Values<String> source() {
  trace.add('source');
  return Values(['first', 'second']);
}

List<String> declared() {
  final result = <String>[];
  for (final value in source()) {
    trace.add('body:$value');
    result.add(value);
  }
  return result;
}

List<String> assigned(Values<String> values) {
  final result = <String>[];
  var value = '';
  for (value in values) {
    trace.add('body:$value');
    result.add(value);
  }
  return result;
}

List<String> collected(Values<String> values) => [
  for (final value in values) value,
];

List<String> patterned(Values<(String, String)> values) => [
  for (final (first, second) in values) '$first:$second',
];

List<String> throughInterface(Iterable<String> values) => [
  for (final value in values) value,
];

void dynamicLoop(dynamic values) {
  for (final value in values) {
    trace.add('$value');
  }
}

Future<void> asyncLoop(Stream<String> values) async {
  await for (final value in values) {
    trace.add(value);
  }
}
