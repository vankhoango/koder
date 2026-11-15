"""A small MCP server for testing Koder's MCP client."""
from mcp.server.mcpserver import MCPServer
import sys

mcp = MCPServer("koder-demo")


@mcp.tool()
def add(a: int, b: int) -> int:
    """Add two whole numbers."""
    return a + b


@mcp.tool()
def word_count(text: str) -> int:
    """Count the words in a text."""
    return len(text.split())


if __name__ == "__main__":
    if "--http" in sys.argv:
        mcp.run(transport="streamable-http")  # serves http://127.0.0.1:8000/mcp by default
    else:
        mcp.run()