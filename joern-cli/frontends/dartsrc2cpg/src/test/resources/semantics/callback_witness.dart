extension Decoration on String {
  String decorate(String value) {
    return '$value.$this';
  }
}

abstract class Sequence {
  Iterable<T> mapIndexed<T>(T Function(int, String) callback);
}

Iterable<MapEntry<String, String>> mapped(Sequence values) =>
    values.mapIndexed((index, element) {
      final prefix = (index + 1).toString();
      return MapEntry(element, element.decorate(prefix));
    });
