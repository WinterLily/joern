import 'dart:collection';

final trace = <String>[];

class Values extends ListBase<String> {
  final List<String> items;
  Values(this.items);

  @override
  int get length {
    trace.add('length');
    return items.length;
  }

  @override
  set length(int value) => items.length = value;

  @override
  String operator [](int index) {
    trace.add('index:$index');
    return items[index];
  }

  @override
  void operator []=(int index, String value) => items[index] = value;

  @override
  List<String> sublist(int start, [int? end = 2]) {
    trace.add('slice:$start:$end');
    return items.sublist(start, end);
  }
}

class OtherValues extends Values {
  OtherValues(super.items);

  @override
  List<String> sublist(int start, [int? end = 1]) {
    trace.add('other:$start:$end');
    return items.sublist(start, end);
  }
}

List<String> allConcrete(Values input) => switch (input) {
  [...var result] => result,
};

List<String> allInterface(List<String> input) => switch (input) {
  [...var result] => result,
};

List<String> head(Values input) => switch (input) {
  [_, ...var result] => result,
  _ => [],
};

List<String> tail(Values input) => switch (input) {
  [...var result, _] => result,
  _ => [],
};

List<String> cached(Values input, bool accept) => switch (input) {
  [_, ...var result] when accept => result,
  [_, ...var result] => result,
  _ => [],
};

List<String> explicitNull(Values input) => input.sublist(0, null);

bool wildcard(Values input) => switch (input) {
  [..._] => true,
};
