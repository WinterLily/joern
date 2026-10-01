import 'default_argument_interactions.dart';

class NamedOverride implements NamedChoice {
  @override
  String pick({
    String ignored = 'override ignored',
    String first = 'override',
    String extra = 'extra',
  }) => first;
}

class NamedState implements NamedChoice {
  final String state;
  NamedState(this.state);

  @override
  String pick({
    String first = 'state unused',
    String ignored = 'state ignored',
    String extra = 'state extra',
  }) => state;
}

class PositionalOverride implements PositionalChoice {
  @override
  String pick([String first = 'positional', String extra = 'extra']) => first;
}

class PositionalExtra implements PositionalChoice {
  @override
  String pick([
    String first = 'positional unused',
    String extra = 'additional positional',
  ]) => extra;
}
