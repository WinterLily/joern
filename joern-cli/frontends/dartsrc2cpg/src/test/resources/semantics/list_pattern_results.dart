import 'dart:collection';

final trace = <String>[];

class Values<T> extends ListBase<T> {
  final List<T> items;
  Values(this.items);

  @override
  int get length {
    trace.add('length');
    return items.length;
  }

  @override
  set length(int value) => items.length = value;

  @override
  T operator [](int index) {
    trace.add('index:$index');
    return items[index];
  }

  @override
  void operator []=(int index, T value) => items[index] = value;

  @override
  Values<T> sublist(int start, [int? end]) {
    trace.add('slice:$start:$end');
    return Values(items.sublist(start, end));
  }
}

abstract interface class Contract {
  String get value;
}

class Store implements Contract {
  final String text;
  Store(this.text);

  @override
  String get value {
    trace.add('store');
    return text;
  }
}

class OtherStore implements Contract {
  final String text;
  OtherStore(this.text);

  @override
  String get value => text;
}

extension type View<S extends Contract>(S representation) implements Contract {}

String scalar(Values<String> input) => switch (input) {
  [var text] => text,
  _ => 'miss',
};

List<String> sliced(Values<String> input) => switch (input) {
  [_, ...var tail] => tail,
  _ => [],
};

String tail(Values<String> input) => switch (input) {
  [_, ..., var text] => text,
  _ => 'miss',
};

String typed(Values<Object> input) => switch (input) {
  [String text] => text,
  _ => 'miss',
};

num cached(Values<num> input) => switch (input) {
  <Object>[int value] => value,
  <num>[var value] => value,
  _ => -1,
};

String wrapped(Values<View<Store>> input) => switch (input) {
  [var view] => view.value,
  _ => 'miss',
};

String nested(Values<Values<View<Store>>> input) => switch (input) {
  [[var view]] => view.value,
  _ => 'miss',
};

T generic<T>(Values<T> input) => switch (input) {
  [var value] => value,
  _ => throw StateError('length'),
};
