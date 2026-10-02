import 'dart:async' as async;

extension type Value<T>(T representation) {}
extension type Values<T>(async.Stream<T> representation)
    implements async.Stream<T> {}

// Implicit iteration uses the SDK protocol despite this library's name shadow.
class StreamIterator<T> {
  T get current => throw StateError('shadow');
  Future<bool> moveNext() => throw StateError('shadow');
  Future<void> cancel() => throw StateError('shadow');
}

Future<List<String>> typed(async.Stream<String> values) async {
  final result = <String>[];
  await for (final value in values) {
    result.add(value);
  }
  return result;
}

Future<List<T>> symbolic<T>(async.Stream<T> values) async => [
  await for (final value in values) value,
];

Future<List<String>> bounded<S extends async.Stream<String>>(S values) async =>
    [await for (final value in values) value];

Future<List<Value<String>>> wrapped(Values<Value<String>> values) async => [
  await for (final value in values) value,
];

Future<List<String>> patterned(async.Stream<(String, int)> values) async => [
  await for (final (name, count) in values) '$name:$count',
];

Future<List<dynamic>> dynamicSource(dynamic values) async => [
  await for (final value in values) value,
];
