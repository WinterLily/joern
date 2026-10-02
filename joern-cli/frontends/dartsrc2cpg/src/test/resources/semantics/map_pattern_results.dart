import 'dart:collection';

final trace = <String>[];

class Entries<K, V> extends MapBase<K, V> {
  final Map<K, V> items;
  final V? absentValue;
  Entries(this.items, [this.absentValue]);

  V? operator [](Object? key) {
    trace.add('index:$key');
    return items.containsKey(key) ? items[key] : absentValue;
  }

  bool containsKey(Object? key) {
    trace.add('contains:$key');
    return items.containsKey(key);
  }

  void operator []=(K key, V value) => items[key] = value;
  Iterable<K> get keys => items.keys;
  void clear() => items.clear();
  V? remove(Object? key) => items.remove(key);
}

class Narrow extends Entries<String, Object?> {
  Narrow(Map<String, String> items) : super(items);
  String? operator [](Object? key) => super[key] as String?;
}

abstract interface class Contract {
  String get value;
}

class Store implements Contract {
  final String text;
  Store(this.text);
  String get value {
    trace.add('store');
    return text;
  }
}

class OtherStore implements Contract {
  final String text;
  OtherStore(this.text);
  String get value {
    trace.add('other');
    return text;
  }
}

extension type View<T extends Contract>(T representation) implements Contract {}
extension type NullableView<T>(T representation) {}
extension type MapView<V>(Entries<String, V> representation)
    implements Map<String, V> {}

String selected(Entries<String, String> input) => switch (input) {
  {'selected': var value} => value,
  _ => 'absent',
};

Object? broad(Narrow input) => switch (input) {
  {'selected': var value} => value,
  _ => 'absent',
};

String? nullable(Entries<String, String?> input) => switch (input) {
  {'selected': var value} => value,
  _ => 'absent',
};

Object? typed(Entries<String, Object?> input) => switch (input) {
  {'selected': String value} => value,
  _ => 'other',
};

Object? cached(Object input, bool accept) => switch (input) {
  <String, Object?>{'selected': var value} when accept => value,
  <String, String?>{'selected': var value} => value,
  _ => 'absent',
};

String wrapped(Entries<String, View<Store>> input) => switch (input) {
  {'selected': View<Store>(:var value)} => value,
  _ => 'absent',
};

Object? nullableWrapper(Entries<String, NullableView<String?>> input) =>
    switch (input) {
      {'selected': var value} => value.representation,
      _ => 'absent',
    };

String outerWrapper(MapView<View<Store>> input) => switch (input) {
  {'selected': View<Store>(:var value)} => value,
  _ => 'absent',
};

String cachedWrapper(Object input, bool accept) => switch (input) {
  <String, View<Contract>>{'selected': View<Contract>(:var value)}
      when accept =>
    value,
  <String, View<Store>>{'selected': View<Store>(:var value)} => value,
  _ => 'absent',
};

T? generic<T>(Entries<String, T> input) => switch (input) {
  {'selected': var value} => value,
  _ => null,
};
