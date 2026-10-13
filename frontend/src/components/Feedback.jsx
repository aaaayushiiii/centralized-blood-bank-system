export default function Feedback({ error, success, loading }) {
  if (error)
    return (
      <div className="feedback error" role="alert">
        {error}
      </div>
    );
  if (loading)
    return (
      <div className="feedback" role="status">
        Loading, please wait…
      </div>
    );
  if (success)
    return (
      <div className="feedback success" role="status">
        {success}
      </div>
    );
  return null;
}
