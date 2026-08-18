namespace OneK8s.DbHello;

/// <summary>Configuration, all of it from the environment.</summary>
internal static class Env
{
    /// <summary>
    /// Unset and set-but-empty are the same thing here: both mean "the chart
    /// did not give us one".
    /// </summary>
    public static string? Value(string name) =>
        Environment.GetEnvironmentVariable(name) is { Length: > 0 } value ? value : null;
}
