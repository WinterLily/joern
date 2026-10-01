import 'dart:collection';

final trace = <String>[];

class LoggedList extends ListBase<Object?> {
  final List<Object?> values;
  LoggedList(this.values);

  @override
  int get length {
    trace.add('length');
    return values.length;
  }

  @override
  set length(int value) => throw UnsupportedError('resize');

  @override
  Object? operator [](int index) {
    trace.add('index:$index');
    return values[index];
  }

  @override
  void operator []=(int index, Object? value) => values[index] = value;

  @override
  List<Object?> sublist(int start, [int? end]) {
    trace.add('slice:$start:$end');
    return values.sublist(start, end);
  }
}

bool wildcards(LoggedList input) => switch (input) {
  [_, _] => true,
  _ => false,
};

bool empty(LoggedList input) => switch (input) {
  [] => true,
  _ => false,
};

List<Object?> all(LoggedList input) => switch (input) {
  [...var values] => values,
};

List<Object?> prefixRest(LoggedList input) => switch (input) {
  [var first, ...var remaining] => [first, remaining],
  _ => [],
};

bool typedWildcard(LoggedList input) => switch (input) {
  [int _, _] => true,
  _ => false,
};

bool restWildcard(LoggedList input) => switch (input) {
  [..._] => true,
};

bool headAndRestWildcard(LoggedList input) => switch (input) {
  [_, ..._] => true,
  _ => false,
};

Object? cases(LoggedList input) => switch (input) {
  [0, _] => 'zero',
  [var value, _] => value,
  _ => 'wrong length',
};

List<Object?> rest(LoggedList input) => switch (input) {
  [var first, ...var middle, var last] => [first, middle, last],
  _ => [],
};

Object? tail(LoggedList input) => switch (input) {
  [_, ..., 0] => 'zero',
  [..., var value] => value,
  _ => 'empty',
};
