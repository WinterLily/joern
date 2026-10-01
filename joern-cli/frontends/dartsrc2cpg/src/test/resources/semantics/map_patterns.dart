import 'dart:collection';

final trace = <String>[];
const selectedKey = 'selected';
const selectedAlias = selectedKey;

class LoggedMap extends MapBase<Object?, Object?> {
  final Map<Object?, Object?> data;
  final bool changeAfterRead;
  LoggedMap(this.data, {this.changeAfterRead = false});

  @override
  Object? operator [](Object? key) {
    trace.add('index:$key');
    final result = data[key];
    if (changeAfterRead && data.containsKey(key)) data[key] = 'changed';
    return result;
  }

  @override
  bool containsKey(Object? key) {
    trace.add('contains:$key');
    return data.containsKey(key);
  }

  @override
  Iterable<Object?> get keys => data.keys;
  @override
  void operator []=(Object? key, Object? value) => data[key] = value;
  @override
  void clear() => data.clear();
  @override
  Object? remove(Object? key) => data.remove(key);
}

bool wildcard(LoggedMap input) => switch (input) {
  {selectedKey: _} => true,
  _ => false,
};

bool typedWildcard(LoggedMap input) => switch (input) {
  {selectedKey: int _} => true,
  _ => false,
};

Object? cases(LoggedMap input) => switch (input) {
  {selectedKey: 0} => 'zero',
  {selectedAlias: var value} => value,
  _ => 'absent',
};

Object? nullKey(LoggedMap input) => switch (input) {
  {null: 0} => 'zero',
  {null: var value} => value,
  _ => 'absent',
};

Object? guarded(LoggedMap input, bool accept) => switch (input) {
  {selectedKey: var value} when accept => value,
  {selectedAlias: var value} => value,
  _ => 'absent',
};

bool multiple(LoggedMap input) => switch (input) {
  {selectedKey: _, 'other': _} => true,
  _ => false,
};

bool typedMap(Object? input) => switch (input) {
  <String, int>{selectedKey: _} => true,
  _ => false,
};

List<Object?> separate(LoggedMap input) {
  final first = switch (input) {
    {selectedKey: var value} => value,
    _ => 'absent',
  };
  final second = switch (input) {
    {selectedKey: var value} => value,
    _ => 'absent',
  };
  return [first, second];
}

class TypedMap extends MapBase<String, int> {
  final Map<String, int> data;
  TypedMap(this.data);

  @override
  int? operator [](Object? key) {
    trace.add('index:$key');
    return data[key];
  }

  @override
  bool containsKey(Object? key) {
    trace.add('contains:$key');
    return data.containsKey(key);
  }

  @override
  Iterable<String> get keys => data.keys;
  @override
  void operator []=(String key, int value) => data[key] = value;
  @override
  void clear() => data.clear();
  @override
  int? remove(Object? key) => data.remove(key);
}

bool generic<T>(Map<String, T> input) => switch (input) {
  {selectedKey: _} => true,
  _ => false,
};

Object? nested(LoggedMap input) => switch (input) {
  {'left': {selectedKey: 0}} => 'zero',
  {'right': {selectedAlias: var value}} => value,
  _ => 'absent',
};

class ScalarMap extends MapBase<String, String> {
  final String value;
  ScalarMap(this.value);

  @override
  String? operator [](Object? key) => value;
  @override
  Iterable<String> get keys => [selectedKey];
  @override
  void operator []=(String key, String value) =>
      throw UnsupportedError('write');
  @override
  void clear() => throw UnsupportedError('clear');
  @override
  String? remove(Object? key) => throw UnsupportedError('remove');
}

String selected(String input) => switch (ScalarMap(input)) {
  {selectedKey: var value} => value,
  _ => 'absent',
};

String independent(String input) {
  final first = ScalarMap(input);
  final second = ScalarMap('other');
  return switch (second) {
    {selectedKey: var value} => value,
    _ => 'absent',
  };
}

String constant(String input) => switch (ScalarMap(input)) {
  {selectedKey: _} => 'constant',
  _ => 'absent',
};

class EntryState {
  final bool state;
  EntryState(this.state);

  bool get containsKey {
    trace.add('member');
    return state;
  }
}

bool nestedMember(LoggedMap input) => switch (input) {
  {selectedKey: EntryState(containsKey: var present)} => present,
  _ => false,
};
