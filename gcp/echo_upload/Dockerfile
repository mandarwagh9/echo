FROM python:3.12-slim
ENV LANG=C.UTF-8 PYTHONIOENCODING=utf-8 PYTHONUNBUFFERED=1
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY main.py .
# Two workers, one thread: this handler does one signed-URL call and returns.
CMD exec gunicorn --bind :$PORT --workers 2 --threads 4 --timeout 30 main:app
