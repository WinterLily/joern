final trace = <String>[];

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

String selected(Store input) => switch (input) {
  Contract(:var value) => value,
};

String wrapped(View<Store> input) => switch (input) {
  Contract(:var value) => value,
};

String bounded<S extends Store>(S input) => switch (input) {
  Contract(:var value) => value,
};

String boundedView<S extends Store>(View<S> input) => switch (input) {
  Contract(:var value) => value,
};

String? nullable(Store? input) => switch (input) {
  Contract(:var value) => value,
  _ => null,
};

String narrowed(Object input) => switch (input) {
  Store(:var value) => value,
  _ => 'absent',
};

String matched(Contract input) => switch (input) {
  Contract(:var value) => value,
};

String cached(Object input, bool accept) => switch (input) {
  Store(:var value) when accept => value,
  Contract(:var value) => value,
  _ => 'absent',
};

String repeated(View<Store> input, bool accept) => switch (input) {
  Contract(:var value) when accept => value,
  Contract(:var value) => value,
};
