abstract interface class Value<T> {
  T get value;
}

final trace = <String>[];

class Store implements Value<String> {
  final String text;
  Store(this.text);
  String get value {
    trace.add('store');
    return text;
  }
}

class OtherStore implements Value<String> {
  final String text;
  OtherStore(this.text);
  String get value {
    trace.add('other');
    return text;
  }
}

extension type View<S extends Value<String>>(S representation)
    implements Value<String> {}

class Holder<S extends Value<String>> {
  final S source;
  Holder(this.source);
  View<S> get view {
    trace.add('holder');
    return View(source);
  }
}

String direct(Object input) => switch (input) {
  View<Store>(value: var text) => text,
  _ => 'miss',
};
String other(Object input) => switch (input) {
  View<OtherStore>(value: var text) => text,
  _ => 'miss',
};
String plain(Object input) => switch (input) {
  Store(value: var text) => text,
  _ => 'miss',
};
String nested(Holder<Store> input) => switch (input) {
  Holder<Store>(view: View<Store>(value: var text)) => text,
};
String captured(Holder<Store> input) => switch (input) {
  Holder<Store>(view: var view) => view.value,
};
String record((View<Store>,) input) => switch (input) {
  (View<Store>(value: var text),) => text,
};
String own(Object input) => switch (input) {
  View<Store>(representation: Store(value: var text)) => text,
  _ => 'miss',
};
String alternatives(Object input) => switch (input) {
  View<Store>(value: 'never') => 'unexpected',
  View<Store>(value: var text) => text,
  View<OtherStore>(value: var text) => text,
  _ => 'miss',
};

String nestedAlternatives(Object input) => switch (input) {
  Holder<Store>(view: View<Store>(value: 'never')) => 'unexpected',
  Holder<Store>(view: View<Store>(value: var text)) => text,
  Holder<OtherStore>(view: View<OtherStore>(value: var text)) => text,
  _ => 'miss',
};
