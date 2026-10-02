import 'synchronous_iteration.dart' as iteration;

abstract interface class Contract {
  String read();
  String get value;
  set value(String value);
  String operator [](int index);
  void operator []=(int index, String value);
}

final trace = <String>[];

class Store implements Contract {
  final List<String> items;
  Store(String value) : items = [value];
  String read() {
    trace.add('read');
    return items.single;
  }

  String get value {
    trace.add('get');
    return items.single;
  }

  set value(String value) {
    trace.add('set');
    items[0] = value;
  }

  String operator [](int index) {
    trace.add('index');
    return items[index];
  }

  void operator []=(int index, String value) {
    trace.add('index-set');
    items[index] = value;
  }
}

class OtherStore implements Contract {
  String read() => 'other';
  String get value => 'other';
  set value(String value) {}
  String operator [](int index) => 'other';
  void operator []=(int index, String value) {}
}

extension type View(Store representation) implements Contract {}
extension type NestedView(View representation) implements Contract {}
extension type GenericView<S extends Contract>(S representation)
    implements Contract {}
extension type Shadow(Store representation) {
  String read() => 'shadow';
}

String direct(View value) => value.read();
String nested(NestedView value) => value.read();
String concrete(GenericView<Store> value) => value.read();
String other(GenericView<OtherStore> value) => value.read();
String bounded<T extends GenericView<Store>>(T value) => value.read();
String? nullable(GenericView<Store>? value) => value?.read();
String own(Shadow value) => value.read();
String getter(GenericView<Store> value) => value.value;
void setter(GenericView<Store> value, String input) {
  value.value = input;
}

String index(GenericView<Store> value) => value[0];
void indexedSetter(GenericView<Store> value, String input) {
  value[0] = input;
}

void cascade(GenericView<Store> value) {
  value
    ..read()
    ..value = 'changed';
}

String Function() tearOff(GenericView<Store> value) => value.read;

extension type ValuesView<T>(iteration.Values<T> representation)
    implements Iterable<T> {}
extension type NestedValuesView<T>(ValuesView<T> representation)
    implements Iterable<T> {}
List<T> wrappedValues<T>(ValuesView<T> values) => [
  for (final value in values) value,
];
List<T> nestedValues<T>(NestedValuesView<T> values) => [
  for (final value in values) value,
];

extension type CursorView<T, I extends Iterator<T>>(I representation)
    implements Iterator<T> {}

class WrappedCursorValues<T> extends Iterable<T> {
  final List<T> items;
  WrappedCursorValues(this.items);
  @override
  CursorView<T, iteration.Cursor<T>> get iterator =>
      CursorView(iteration.Cursor(items));
}

List<T> wrappedCursor<T>(WrappedCursorValues<T> values) => [
  for (final value in values) value,
];
List<String> wrappedCurrent(WrappedCursorValues<GenericView<Store>> values) => [
  for (final value in values) value.read(),
];
